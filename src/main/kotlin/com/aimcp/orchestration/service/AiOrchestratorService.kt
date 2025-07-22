// service/AiOrchestratorService.kt
package com.aimcp.orchestration.service

import com.aimcp.orchestration.client.AiProviderClient
import com.aimcp.orchestration.config.AiProviderConfig
import com.aimcp.orchestration.config.OrchestrationConfig
import com.aimcp.orchestration.model.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Service
class AiOrchestratorService(
    private val aiProviderClient: AiProviderClient,
    private val mcpCoordinatorService: McpCoordinatorService,
    private val contextManagerService: ContextManagerService,
    private val aiProviderConfig: AiProviderConfig,
    private val orchestrationConfig: OrchestrationConfig
) {

    private val logger = LoggerFactory.getLogger(AiOrchestratorService::class.java)
    private val concurrencyLimiter = Semaphore(orchestrationConfig.maxConcurrentRequests)
    private val objectMapper = jacksonObjectMapper()

    suspend fun processRequest(request: OrchestrationRequest): OrchestrationResponse {
        val requestId = UUID.randomUUID().toString()
        val sessionId = request.sessionId ?: UUID.randomUUID().toString()
        val startTime = Instant.now()

        // Apply concurrency limiting
        if (!concurrencyLimiter.tryAcquire(
                request.preferences.timeout,
                TimeUnit.MILLISECONDS
            )) {
            return createErrorResponse(
                requestId,
                sessionId,
                "Request queue is full. Please try again later.",
                startTime
            )
        }

        try {
            logger.info("Processing orchestration request $requestId for session $sessionId")

            // Get or create conversation context
            val context = contextManagerService.getOrCreateContext(sessionId)

            // Add user message to context
            context.messages.add(ContextMessage("user", request.message))

            // Get available MCP tools with health checks
            val availableTools = mcpCoordinatorService.getAvailableTools()
            context.availableTools.clear()
            context.availableTools.addAll(availableTools)

            logger.debug("Found ${availableTools.size} available tools across ${availableTools.map { it.serverName }.distinct().size} servers")

            // Process with AI orchestration
            val executionSteps = mutableListOf<ExecutionStep>()
            var currentResponse = request.message
            val maxSteps = request.preferences.maxSteps.coerceAtMost(orchestrationConfig.defaultMaxSteps)
            var remainingSteps = maxSteps

            while (remainingSteps > 0) {
                // Check timeout
                /*val elapsed = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                if (elapsed > request.preferences.timeout) {
                    logger.warn("Request $requestId timed out after ${elapsed}ms")
                    break
                }*/

                // Analyze intent and determine required actions
                val aiAnalysis = analyzeUserIntent(currentResponse, context, availableTools, request.preferences)
                executionSteps.add(createAnalysisStep(aiAnalysis))

                // Check if AI wants to call MCP tools
                val toolCalls = extractToolCalls(aiAnalysis.response)

                if (toolCalls.isEmpty()) {
                    // No more tool calls needed, return final response
                    context.messages.add(ContextMessage("assistant", aiAnalysis.response))
                    contextManagerService.updateContext(context)
                    currentResponse = aiAnalysis.response
                    break
                }

                logger.debug("AI requested ${toolCalls.size} tool calls: ${toolCalls.map { "${it.serverName}.${it.toolName}" }}")

                // Execute MCP tool calls
                val toolResults = mutableListOf<String>()
                for (toolCall in toolCalls) {
                    val result = mcpCoordinatorService.executeTool(toolCall)
                    toolResults.add(result.output ?: "No output")
                    executionSteps.add(result)

                    // Add tool result to context for AI synthesis
                    context.executionHistory.add(result)
                }

                // Synthesize results with AI
                currentResponse = synthesizeResults(request.message, toolResults, context, request.preferences)
                remainingSteps--
            }

            val endTime = Instant.now()
            val duration = endTime.toEpochMilli() - startTime.toEpochMilli()

            val response = OrchestrationResponse(
                requestId = requestId,
                sessionId = sessionId,
                status = if (remainingSteps > 0) "success" else "partial",
                response = currentResponse,
                executionFlow = executionSteps,
                metadata = ResponseMetadata(
                    totalDuration = duration,
                    stepsExecuted = executionSteps.size,
                    serversUsed = executionSteps.mapNotNull { it.serverName }.distinct(),
                    toolsUsed = executionSteps.mapNotNull { it.toolName }.distinct(),
                    performance = mapOf(
                        "aiProviderUsed" to (request.preferences.aiProvider ?: aiProviderConfig.defaultProvider),
                        "toolsAvailable" to availableTools.size,
                        "maxStepsReached" to (remainingSteps == 0)
                    )
                )
            )

            logger.info("Completed request $requestId in ${duration}ms with ${executionSteps.size} steps")
            return response

        } catch (e: Exception) {
            logger.error("Error processing request $requestId", e)
            return createErrorResponse(requestId, sessionId, "An error occurred: ${e.message}", startTime)
        } finally {
            concurrencyLimiter.release()
        }
    }

    private suspend fun analyzeUserIntent(
        message: String,
        context: ConversationContext,
        availableTools: List<McpTool>,
        preferences: OrchestrationPreferences
    ): AiAnalysisResult {
        val systemPrompt = buildSystemPrompt(availableTools)
        val conversationHistory = buildConversationHistory(context)
        val aiProvider = preferences.aiProvider ?: aiProviderConfig.defaultProvider

        return aiProviderClient.analyze(
            systemPrompt = systemPrompt,
            userMessage = message,
            conversationHistory = conversationHistory,
            availableTools = availableTools,
            aiProvider = aiProvider,
            preferences = preferences
        )
    }

    private suspend fun synthesizeResults(
        originalMessage: String,
        toolResults: List<String>,
        context: ConversationContext,
        preferences: OrchestrationPreferences
    ): String {
        val synthesisPrompt = buildSynthesisPrompt(originalMessage, toolResults, preferences)
        val aiProvider = preferences.aiProvider ?: aiProviderConfig.defaultProvider

        return aiProviderClient.synthesize(
            prompt = synthesisPrompt,
            context = context,
            aiProvider = aiProvider,
            preferences = preferences
        )
    }

    private fun buildSystemPrompt(availableTools: List<McpTool>): String {
        val toolsByServer = availableTools.groupBy { it.serverName }

        return """
            You are an AI orchestrator that helps users interact with various systems through MCP (Model Context Protocol) tools.
            
            Available MCP servers and their tools:
            ${toolsByServer.entries.joinToString("\n\n") { (serverName, tools) ->
            "Server: $serverName\n" + tools.joinToString("\n") { "  - ${it.name}: ${it.description}" }
        }}
            
            When you need to use tools to fulfill a user's request, respond with a JSON object in this format:
            {
                "action": "tool_call",
                "reasoning": "Why these tools are needed",
                "tool_calls": [
                    {
                        "server_name": "server_name",
                        "tool_name": "tool_name", 
                        "arguments": {"key": "value"}
                    }
                ]
            }
            
            When you have enough information to provide a final answer, respond normally without the JSON format.
            
            Guidelines:
            - Always explain your reasoning when calling tools
            - Use the most appropriate tools for the task
            - Consider tool execution order and dependencies
            - Provide helpful error messages if tools fail
            - Keep responses concise but informative
        """.trimIndent()
    }

    private fun buildSynthesisPrompt(
        originalMessage: String,
        toolResults: List<String>,
        preferences: OrchestrationPreferences
    ): String {
        return when (preferences.responseFormat) {
            "summary" -> """
                Based on the user's request: "$originalMessage"
                
                And the following tool execution results:
                ${toolResults.joinToString("\n") { "- $it" }}
                
                Please provide a concise summary response to the user.
            """.trimIndent()

            "detailed" -> """
                Based on the user's request: "$originalMessage"
                
                Tool execution results:
                ${toolResults.mapIndexed { index, result -> "${index + 1}. $result" }.joinToString("\n")}
                
                Please provide a comprehensive response to the user, including:
                - Summary of what was found/accomplished
                - Key insights from the data
                - Any recommendations or next steps
                - Technical details where relevant
            """.trimIndent()

            "raw" -> """
                User request: "$originalMessage"
                
                Raw results: ${toolResults.joinToString("; ")}
                
                Please format these results for the user.
            """.trimIndent()

            else -> """
                User request: "$originalMessage"
                
                Results: ${toolResults.joinToString("; ")}
                
                Please provide an appropriate response to the user.
            """.trimIndent()
        }
    }

    private fun buildConversationHistory(context: ConversationContext): String {
        return context.messages.takeLast(10).joinToString("\n") {
            "${it.role.replaceFirstChar { char -> char.uppercase() }}: ${it.content}"
        }
    }

    private fun extractToolCalls(aiResponse: String): List<McpToolCall> {
        return try {
            if (!aiResponse.contains("\"action\"") || !aiResponse.contains("\"tool_call\"")) {
                return emptyList()
            }

            // Extract JSON from the response
            val jsonStart = aiResponse.indexOf('{')
            val jsonEnd = aiResponse.lastIndexOf('}') + 1

            if (jsonStart == -1 || jsonEnd <= jsonStart) {
                return emptyList()
            }

            val jsonString = aiResponse.substring(jsonStart, jsonEnd)
            val json = parseJson(jsonString)

            val action = json["action"] as? String
            if (action != "tool_call") {
                return emptyList()
            }

            val toolCalls = json["tool_calls"] as? List<Map<String, Any>> ?: return emptyList()

            toolCalls.mapNotNull { toolCall ->
                try {
                    McpToolCall(
                        serverName = toolCall["server_name"] as String,
                        toolName = toolCall["tool_name"] as String,
                        arguments = toolCall["arguments"] as? Map<String, Any> ?: emptyMap()
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to parse tool call: $toolCall", e)
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract tool calls from AI response", e)
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(jsonString: String): Map<String, Any> {
        return try {
            objectMapper.readValue(jsonString, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            logger.error("Failed to parse JSON: $jsonString", e)
            emptyMap()
        }
    }

    private fun createAnalysisStep(analysis: AiAnalysisResult): ExecutionStep {
        return ExecutionStep(
            stepId = UUID.randomUUID().toString(),
            type = "ai_analysis",
            timestamp = Instant.now(),
            duration = analysis.duration,
            serverName = null,
            toolName = null,
            input = analysis.input,
            output = analysis.response,
            success = true,
            metadata = mapOf(
                "aiProvider" to analysis.aiProvider,
                "tokensUsed" to analysis.tokensUsed
            )
        )
    }

    private fun createErrorResponse(
        requestId: String,
        sessionId: String?,
        errorMessage: String,
        startTime: Instant
    ): OrchestrationResponse {
        return OrchestrationResponse(
            requestId = requestId,
            sessionId = sessionId,
            status = "error",
            response = errorMessage,
            executionFlow = emptyList(),
            metadata = ResponseMetadata(
                totalDuration = Instant.now().toEpochMilli() - startTime.toEpochMilli(),
                stepsExecuted = 0,
                serversUsed = emptyList(),
                toolsUsed = emptyList()
            )
        )
    }

    fun getOrchestrationMetrics(): OrchestrationMetrics {
        return OrchestrationMetrics(
            activeRequests = orchestrationConfig.maxConcurrentRequests - concurrencyLimiter.availablePermits(),
            maxConcurrentRequests = orchestrationConfig.maxConcurrentRequests,
            queueLength = concurrencyLimiter.queueLength
        )
    }
}

data class AiAnalysisResult(
    val response: String,
    val duration: Long,
    val input: String,
    val aiProvider: String,
    val tokensUsed: Int = 0
)

data class OrchestrationMetrics(
    val activeRequests: Int,
    val maxConcurrentRequests: Int,
    val queueLength: Int
)