// client/AiProviderClient.kt - Fixed Type Issues
package com.aimcp.orchestration.client

import com.aimcp.orchestration.config.AiProviderConfig
import com.aimcp.orchestration.model.*
import com.aimcp.orchestration.service.AiAnalysisResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Instant

@Component
class AiProviderClient(
    private val aiProviderConfig: AiProviderConfig,
    private val webClient: WebClient.Builder
) {

    private val logger = LoggerFactory.getLogger(AiProviderClient::class.java)

    suspend fun analyze(
        systemPrompt: String,
        userMessage: String,
        conversationHistory: String,
        availableTools: List<McpTool>,
        aiProvider: String,
        preferences: OrchestrationPreferences
    ): AiAnalysisResult {
        val startTime = Instant.now()

        val result = when (aiProvider) {
            "claude" -> analyzeWithClaude(systemPrompt, userMessage, conversationHistory, preferences)
            "openai" -> analyzeWithOpenAI(systemPrompt, userMessage, conversationHistory, preferences)
            "gemini" -> analyzeWithGemini(systemPrompt, userMessage, conversationHistory, preferences)
            else -> throw IllegalArgumentException("Unknown AI provider: $aiProvider")
        }

        val duration = Instant.now().toEpochMilli() - startTime.toEpochMilli()

        return result.copy(
            duration = duration,
            aiProvider = aiProvider
        )
    }

    suspend fun synthesize(
        prompt: String,
        context: ConversationContext,
        aiProvider: String,
        preferences: OrchestrationPreferences
    ): String {
        return when (aiProvider) {
            "claude" -> synthesizeWithClaude(prompt, context, preferences)
            "openai" -> synthesizeWithOpenAI(prompt, context, preferences)
            "gemini" -> synthesizeWithGemini(prompt, context, preferences)
            else -> throw IllegalArgumentException("Unknown AI provider: $aiProvider")
        }
    }

    private suspend fun analyzeWithClaude(
        systemPrompt: String,
        userMessage: String,
        conversationHistory: String,
        preferences: OrchestrationPreferences
    ): AiAnalysisResult {
        val claudeConfig = aiProviderConfig.claude

        if (!claudeConfig.enabled || claudeConfig.apiKey.isBlank()) {
            throw IllegalStateException("Claude is not properly configured")
        }

        val client = webClient
            .baseUrl("https://api.anthropic.com")
            .defaultHeader("anthropic-version", "2023-06-01")
            .defaultHeader("x-api-key", claudeConfig.apiKey)
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)
            }
            .build()

        val messages = mutableListOf<Map<String, Any>>()

        // Add conversation history if available
        if (conversationHistory.isNotBlank()) {
            messages.add(mapOf<String, Any>("role" to "user", "content" to conversationHistory))
        }

        // Add current user message
        messages.add(mapOf<String, Any>("role" to "user", "content" to userMessage))

        val request = mapOf<String, Any>(
            "model" to claudeConfig.model,
            "max_tokens" to (preferences.maxTokens.takeIf { it > 0 } ?: claudeConfig.maxTokens),
            "temperature" to (preferences.temperature.takeIf { it >= 0 } ?: claudeConfig.temperature),
            "system" to systemPrompt,
            "messages" to messages
        )

        logger.debug("Sending request to Claude API with model ${claudeConfig.model}")

        val response = client.post()
            .uri("/v1/messages")
            .bodyValue(request)
            .retrieve()
            .awaitBody<Map<String, Any>>()

        val content = (response["content"] as List<Map<String, Any>>)
            .first()["text"] as String

        val usage = response["usage"] as? Map<String, Any>
        val tokensUsed = (usage?.get("input_tokens") as? Int ?: 0) + (usage?.get("output_tokens") as? Int ?: 0)

        return AiAnalysisResult(
            response = content,
            duration = 0, // Will be set by caller
            input = userMessage,
            aiProvider = "claude",
            tokensUsed = tokensUsed
        )
    }

    private suspend fun synthesizeWithClaude(
        prompt: String,
        context: ConversationContext,
        preferences: OrchestrationPreferences
    ): String {
        val claudeConfig = aiProviderConfig.claude

        val client = webClient
            .baseUrl("https://api.anthropic.com")
            .defaultHeader("anthropic-version", "2023-06-01")
            .defaultHeader("x-api-key", claudeConfig.apiKey)
            .build()

        val request = mapOf<String, Any>(
            "model" to claudeConfig.model,
            "max_tokens" to (preferences.maxTokens.takeIf { it > 0 } ?: claudeConfig.maxTokens),
            "temperature" to (preferences.temperature.takeIf { it >= 0 } ?: claudeConfig.temperature),
            "messages" to listOf(
                mapOf<String, Any>("role" to "user", "content" to prompt)
            )
        )

        val response = client.post()
            .uri("/v1/messages")
            .bodyValue(request)
            .retrieve()
            .awaitBody<Map<String, Any>>()

        return (response["content"] as List<Map<String, Any>>)
            .first()["text"] as String
    }

    private suspend fun analyzeWithOpenAI(
        systemPrompt: String,
        userMessage: String,
        conversationHistory: String,
        preferences: OrchestrationPreferences
    ): AiAnalysisResult {
        val openaiConfig = aiProviderConfig.openai

        if (!openaiConfig.enabled || openaiConfig.apiKey.isBlank()) {
            throw IllegalStateException("OpenAI is not properly configured")
        }

        val client = webClient
            .baseUrl("https://api.openai.com")
            .defaultHeader("Authorization", "Bearer ${openaiConfig.apiKey}")
            .build()

        val messages = mutableListOf<Map<String, Any>>()
        messages.add(mapOf<String, Any>("role" to "system", "content" to systemPrompt))

        if (conversationHistory.isNotBlank()) {
            messages.add(mapOf<String, Any>("role" to "assistant", "content" to conversationHistory))
        }

        messages.add(mapOf<String, Any>("role" to "user", "content" to userMessage))

        val request = mapOf<String, Any>(
            "model" to openaiConfig.model,
            "messages" to messages,
            "max_tokens" to (preferences.maxTokens.takeIf { it > 0 } ?: openaiConfig.maxTokens),
            "temperature" to (preferences.temperature.takeIf { it >= 0 } ?: openaiConfig.temperature)
        )

        logger.debug("Sending request to OpenAI API with model ${openaiConfig.model}")

        val response = client.post()
            .uri("/v1/chat/completions")
            .bodyValue(request)
            .retrieve()
            .awaitBody<Map<String, Any>>()

        val choices = response["choices"] as List<Map<String, Any>>
        val message = choices.first()["message"] as Map<String, Any>
        val content = message["content"] as String

        val usage = response["usage"] as? Map<String, Any>
        val tokensUsed = usage?.get("total_tokens") as? Int ?: 0

        return AiAnalysisResult(
            response = content,
            duration = 0,
            input = userMessage,
            aiProvider = "openai",
            tokensUsed = tokensUsed
        )
    }

    private suspend fun synthesizeWithOpenAI(
        prompt: String,
        context: ConversationContext,
        preferences: OrchestrationPreferences
    ): String {
        val openaiConfig = aiProviderConfig.openai

        val client = webClient
            .baseUrl("https://api.openai.com")
            .defaultHeader("Authorization", "Bearer ${openaiConfig.apiKey}")
            .build()

        val request = mapOf<String, Any>(
            "model" to openaiConfig.model,
            "messages" to listOf(
                mapOf<String, Any>("role" to "user", "content" to prompt)
            ),
            "max_tokens" to (preferences.maxTokens.takeIf { it > 0 } ?: openaiConfig.maxTokens),
            "temperature" to (preferences.temperature.takeIf { it >= 0 } ?: openaiConfig.temperature)
        )

        val response = client.post()
            .uri("/v1/chat/completions")
            .bodyValue(request)
            .retrieve()
            .awaitBody<Map<String, Any>>()

        val choices = response["choices"] as List<Map<String, Any>>
        val message = choices.first()["message"] as Map<String, Any>
        return message["content"] as String
    }

    private suspend fun analyzeWithGemini(
        systemPrompt: String,
        userMessage: String,
        conversationHistory: String,
        preferences: OrchestrationPreferences
    ): AiAnalysisResult {
        val geminiConfig = aiProviderConfig.gemini

        if (!geminiConfig.enabled || geminiConfig.apiKey.isBlank()) {
            throw IllegalStateException("Gemini is not properly configured")
        }

        val client = webClient
            .baseUrl("https://generativelanguage.googleapis.com")
            .build()

        val prompt = buildString {
            append(systemPrompt)
            append("\n\n")
            if (conversationHistory.isNotBlank()) {
                append("Conversation history:\n")
                append(conversationHistory)
                append("\n\n")
            }
            append("User: ")
            append(userMessage)
        }

        val request = mapOf<String, Any>(
            "contents" to listOf(
                mapOf<String, Any>("parts" to listOf(mapOf<String, Any>("text" to prompt)))
            ),
            "generationConfig" to mapOf<String, Any>(
                "temperature" to (preferences.temperature.takeIf { it >= 0 } ?: geminiConfig.temperature),
                "maxOutputTokens" to (preferences.maxTokens.takeIf { it > 0 } ?: geminiConfig.maxTokens)
            )
        )

        logger.debug("Sending request to Gemini API with model ${geminiConfig.model}")

        val response = client.post()
            .uri("/v1beta/models/${geminiConfig.model}:generateContent?key=${geminiConfig.apiKey}")
            .bodyValue(request)
            .retrieve()
            .awaitBody<Map<String, Any>>()

        val candidates = response["candidates"] as List<Map<String, Any>>
        val content = candidates.first()["content"] as Map<String, Any>
        val parts = content["parts"] as List<Map<String, Any>>
        val text = parts.first()["text"] as String

        val usage = response["usageMetadata"] as? Map<String, Any>
        val tokensUsed = usage?.get("totalTokenCount") as? Int ?: 0

        return AiAnalysisResult(
            response = text,
            duration = 0,
            input = userMessage,
            aiProvider = "gemini",
            tokensUsed = tokensUsed
        )
    }

    private suspend fun synthesizeWithGemini(
        prompt: String,
        context: ConversationContext,
        preferences: OrchestrationPreferences
    ): String {
        val geminiConfig = aiProviderConfig.gemini

        val client = webClient
            .baseUrl("https://generativelanguage.googleapis.com")
            .build()

        val request = mapOf<String, Any>(
            "contents" to listOf(
                mapOf<String, Any>("parts" to listOf(mapOf<String, Any>("text" to prompt)))
            ),
            "generationConfig" to mapOf<String, Any>(
                "temperature" to (preferences.temperature.takeIf { it >= 0 } ?: geminiConfig.temperature),
                "maxOutputTokens" to (preferences.maxTokens.takeIf { it > 0 } ?: geminiConfig.maxTokens)
            )
        )

        val response = client.post()
            .uri("/v1beta/models/${geminiConfig.model}:generateContent?key=${geminiConfig.apiKey}")
            .bodyValue(request)
            .retrieve()
            .awaitBody<Map<String, Any>>()

        val candidates = response["candidates"] as List<Map<String, Any>>
        val content = candidates.first()["content"] as Map<String, Any>
        val parts = content["parts"] as List<Map<String, Any>>
        return parts.first()["text"] as String
    }
}