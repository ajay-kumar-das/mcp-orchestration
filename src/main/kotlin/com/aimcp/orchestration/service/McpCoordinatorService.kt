// service/McpCoordinatorService.kt
package com.aimcp.orchestration.service

import com.aimcp.orchestration.client.McpClient
import com.aimcp.orchestration.config.McpServerConfig
import com.aimcp.orchestration.config.McpServerDefinition
import com.aimcp.orchestration.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Service
class McpCoordinatorService(
    private val mcpClient: McpClient,
    private val mcpServerConfig: McpServerConfig
) {

    private val logger = LoggerFactory.getLogger(McpCoordinatorService::class.java)
    private val serverCapabilities = ConcurrentHashMap<String, ServerCapabilities>()
    private val cachedTools = ConcurrentHashMap<String, List<McpTool>>()

    suspend fun getAvailableTools(): List<McpTool> {
        val allTools = mutableListOf<McpTool>()

        coroutineScope {
            val toolDiscoveryTasks = mcpServerConfig.servers
                .filter { (_, config) -> config.enabled && config.isHealthy }
                .map { (serverName, serverConfig) ->
                    async {
                        try {
                            discoverToolsForServer(serverName, serverConfig)
                        } catch (e: Exception) {
                            logger.warn("Failed to discover tools from server $serverName: ${e.message}")
                            emptyList()
                        }
                    }
                }

            val results = toolDiscoveryTasks.awaitAll()
            results.forEach { tools -> allTools.addAll(tools) }
        }

        return allTools.sortedWith(compareBy({ it.serverName }, { it.name }))
    }

    private suspend fun discoverToolsForServer(
        serverName: String,
        serverConfig: McpServerDefinition
    ): List<McpTool> {
        // Check cache first
        val cacheKey = "$serverName:${serverConfig.baseUrl}"
        cachedTools[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - serverConfig.lastHealthCheck < 300000) { // 5 min cache
                return cached
            }
        }

        return try {
            // Initialize connection if needed
            if (!serverCapabilities.containsKey(serverName)) {
                initializeServer(serverName, serverConfig)
            }

            val tools = mcpClient.discoverTools(serverName, serverConfig)
            cachedTools[cacheKey] = tools

            logger.info("Discovered ${tools.size} tools from server $serverName")
            tools
        } catch (e: Exception) {
            logger.error("Failed to discover tools from server $serverName", e)
            cachedTools.remove(cacheKey)
            emptyList()
        }
    }

    private suspend fun initializeServer(serverName: String, serverConfig: McpServerDefinition) {
        try {
            val capabilities = mcpClient.initializeServer(serverName, serverConfig)
            serverCapabilities[serverName] = capabilities
            serverConfig.isHealthy = true
            serverConfig.lastHealthCheck = System.currentTimeMillis()

            logger.info("Initialized server $serverName with capabilities: ${capabilities.supportedFeatures}")
        } catch (e: Exception) {
            logger.error("Failed to initialize server $serverName", e)
            serverConfig.isHealthy = false
            throw e
        }
    }

    suspend fun executeTool(toolCall: McpToolCall): ExecutionStep {
        val stepId = UUID.randomUUID().toString()
        val startTime = Instant.now()

        return try {
            val serverConfig = mcpServerConfig.servers[toolCall.serverName]
                ?: throw IllegalArgumentException("Server ${toolCall.serverName} not found")

            if (!serverConfig.enabled) {
                throw IllegalStateException("Server ${toolCall.serverName} is disabled")
            }

            if (!serverConfig.isHealthy) {
                throw IllegalStateException("Server ${toolCall.serverName} is unhealthy")
            }

            val result = mcpClient.executeTool(
                serverName = toolCall.serverName,
                serverConfig = serverConfig,
                toolName = toolCall.toolName,
                arguments = toolCall.arguments
            )

            val endTime = Instant.now()
            val duration = endTime.toEpochMilli() - startTime.toEpochMilli()

            ExecutionStep(
                stepId = stepId,
                type = "mcp_call",
                timestamp = startTime,
                duration = duration,
                serverName = toolCall.serverName,
                toolName = toolCall.toolName,
                input = toolCall.arguments.toString(),
                output = result,
                success = true
            )

        } catch (e: Exception) {
            val endTime = Instant.now()
            val duration = endTime.toEpochMilli() - startTime.toEpochMilli()

            logger.error("Tool execution failed for ${toolCall.toolName} on ${toolCall.serverName}", e)

            // Mark server as unhealthy if connection issues
            if (e is java.net.ConnectException || e is java.net.SocketTimeoutException) {
                mcpServerConfig.servers[toolCall.serverName]?.isHealthy = false
            }

            ExecutionStep(
                stepId = stepId,
                type = "mcp_call",
                timestamp = startTime,
                duration = duration,
                serverName = toolCall.serverName,
                toolName = toolCall.toolName,
                input = toolCall.arguments.toString(),
                output = "Error: ${e.message}",
                success = false
            )
        }
    }

    suspend fun testServerConnection(serverName: String): Boolean {
        val serverConfig = mcpServerConfig.servers[serverName] ?: return false
        return try {
            val isConnected = mcpClient.testConnection(serverName, serverConfig)
            serverConfig.isHealthy = isConnected
            serverConfig.lastHealthCheck = System.currentTimeMillis()

            if (isConnected) {
                logger.debug("Health check passed for server $serverName")
            } else {
                logger.warn("Health check failed for server $serverName")
                // Clear cached tools for unhealthy server
                cachedTools.keys.removeAll { it.startsWith("$serverName:") }
            }

            isConnected
        } catch (e: Exception) {
            logger.error("Health check error for server $serverName", e)
            serverConfig.isHealthy = false
            serverConfig.lastHealthCheck = System.currentTimeMillis()
            false
        }
    }

    @Scheduled(fixedDelayString = "#{@mcpServerConfig.healthCheckInterval}")
    suspend fun performHealthChecks() {
        if (!mcpServerConfig.autoDiscoveryEnabled) {
            return
        }

        logger.debug("Performing scheduled health checks for ${mcpServerConfig.servers.size} servers")

        coroutineScope {
            val healthCheckTasks = mcpServerConfig.servers.map { (serverName, _) ->
                async {
                    testServerConnection(serverName)
                }
            }

            healthCheckTasks.awaitAll()
        }

        val healthyServers = mcpServerConfig.servers.count { it.value.isHealthy }
        val totalServers = mcpServerConfig.servers.size

        logger.info("Health check completed: $healthyServers/$totalServers servers healthy")
    }

    fun getServerCapabilities(serverName: String): ServerCapabilities? {
        return serverCapabilities[serverName]
    }

    fun getServerHealth(): Map<String, ServerHealth> {
        return mcpServerConfig.servers.mapValues { (_, config) ->
            ServerHealth(
                isHealthy = config.isHealthy,
                lastChecked = config.lastHealthCheck,
                capabilities = serverCapabilities[config.name]?.supportedFeatures ?: emptyList()
            )
        }
    }

    fun invalidateToolCache(serverName: String? = null) {
        if (serverName != null) {
            cachedTools.keys.removeAll { it.startsWith("$serverName:") }
        } else {
            cachedTools.clear()
        }
    }
}

data class ServerCapabilities(
    val protocolVersion: String,
    val supportedFeatures: List<String>,
    val serverInfo: Map<String, Any> = emptyMap()
)

data class ServerHealth(
    val isHealthy: Boolean,
    val lastChecked: Long,
    val capabilities: List<String>
)