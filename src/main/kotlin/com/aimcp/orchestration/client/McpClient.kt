// client/McpClient.kt
package com.aimcp.orchestration.client

import com.aimcp.orchestration.config.AuthType
import com.aimcp.orchestration.config.McpServerDefinition
import com.aimcp.orchestration.model.*
import com.aimcp.orchestration.security.CredentialManager
import com.aimcp.orchestration.service.ServerCapabilities
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Duration
import java.util.*

@Component
class McpClient(
    private val webClient: WebClient.Builder,
    private val credentialManager: CredentialManager
) {

    private val logger = LoggerFactory.getLogger(McpClient::class.java)

    suspend fun initializeServer(serverName: String, serverConfig: McpServerDefinition): ServerCapabilities {
        val client = createWebClient(serverConfig)

        val request = McpRequest(
            id = UUID.randomUUID().toString(),
            method = "initialize",
            params = mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to mapOf<String, Any>(),
                "clientInfo" to mapOf(
                    "name" to "ai-mcp-orchestrator",
                    "version" to "1.0.0"
                )
            )
        )

        logger.debug("Initializing server $serverName at ${serverConfig.baseUrl}")

        val response = client.post()
            .uri("/mcp")
            .bodyValue(request)
            .retrieve()
            .awaitBody<McpResponse>()

        if (response.error != null) {
            throw RuntimeException("MCP initialization failed: ${response.error.message}")
        }

        val result = response.result as? Map<String, Any> ?: throw RuntimeException("Invalid initialization response")
        val capabilities = result["capabilities"] as? Map<String, Any> ?: emptyMap()
        val serverInfo = result["serverInfo"] as? Map<String, Any> ?: emptyMap()

        return ServerCapabilities(
            protocolVersion = result["protocolVersion"] as? String ?: "unknown",
            supportedFeatures = extractSupportedFeatures(capabilities),
            serverInfo = serverInfo
        )
    }

    private fun extractSupportedFeatures(capabilities: Map<String, Any>): List<String> {
        val features = mutableListOf<String>()

        capabilities.forEach { (key, value) ->
            when (key) {
                "tools" -> if (value != null) features.add("tools")
                "resources" -> if (value != null) features.add("resources")
                "prompts" -> if (value != null) features.add("prompts")
                "logging" -> if (value != null) features.add("logging")
                else -> {
                    // Handle other capabilities
                    if (value != null) features.add(key)
                }
            }
        }

        return features
    }

    suspend fun discoverTools(serverName: String, serverConfig: McpServerDefinition): List<McpTool> {
        val client = createWebClient(serverConfig)

        val request = McpRequest(
            id = UUID.randomUUID().toString(),
            method = "tools/list"
        )

        logger.debug("Discovering tools for server $serverName")

        val response = client.post()
            .uri("/mcp")
            .bodyValue(request)
            .retrieve()
            .awaitBody<McpResponse>()

        if (response.error != null) {
            throw RuntimeException("Tool discovery failed: ${response.error.message}")
        }

        val result = response.result as? Map<String, Any> ?: return emptyList()
        val toolsList = result["tools"] as? List<Map<String, Any>> ?: return emptyList()

        val tools = toolsList.mapNotNull { tool ->
            try {
                McpTool(
                    name = tool["name"] as String,
                    description = tool["description"] as? String ?: "",
                    inputSchema = tool["inputSchema"] as? Map<String, Any> ?: emptyMap(),
                    serverName = serverName
                )
            } catch (e: Exception) {
                logger.warn("Failed to parse tool definition: $tool", e)
                null
            }
        }

        logger.info("Discovered ${tools.size} tools from server $serverName: ${tools.map { it.name }}")
        return tools
    }

    suspend fun executeTool(
        serverName: String,
        serverConfig: McpServerDefinition,
        toolName: String,
        arguments: Map<String, Any>
    ): String {
        val client = createWebClient(serverConfig)

        val request = McpRequest(
            id = UUID.randomUUID().toString(),
            method = "tools/call",
            params = mapOf(
                "name" to toolName,
                "arguments" to arguments
            )
        )

        logger.debug("Executing tool $toolName on server $serverName with arguments: $arguments")

        val response = client.post()
            .uri("/mcp")
            .bodyValue(request)
            .retrieve()
            .awaitBody<McpResponse>()

        return when {
            response.error != null -> {
                logger.error("Tool execution failed: ${response.error.message}")
                "Error: ${response.error.message}"
            }
            response.result != null -> {
                val result = response.result as? Map<String, Any>
                val content = result?.get("content") as? List<Map<String, Any>>
                val textContent = content?.mapNotNull { it["text"] as? String }?.joinToString("\n")

                if (textContent.isNullOrBlank()) {
                    // Try to get result directly if no content structure
                    result?.toString() ?: "Tool executed successfully"
                } else {
                    textContent
                }
            }
            else -> "No result returned"
        }
    }

    suspend fun testConnection(serverName: String, serverConfig: McpServerDefinition): Boolean {
        return try {
            val client = createWebClient(serverConfig)

            // Try a simple ping/health check first
            try {
                client.get()
                    .uri("/health")
                    .retrieve()
                    .awaitBody<String>()
                return true
            } catch (e: Exception) {
                // If health endpoint doesn't exist, try MCP initialize
                initializeServer(serverName, serverConfig)
                true
            }
        } catch (e: Exception) {
            logger.debug("Connection test failed for server $serverName: ${e.message}")
            false
        }
    }

    private fun createWebClient(serverConfig: McpServerDefinition): WebClient {
        val builder = webClient
            .baseUrl(serverConfig.baseUrl)
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) // 16MB
            }

        // Configure timeout
        val timeout = Duration.ofMillis(serverConfig.timeout)
        builder.clientConnector(
            org.springframework.http.client.reactive.ReactorClientHttpConnector(
                reactor.netty.http.client.HttpClient.create()
                    .responseTimeout(timeout)
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, serverConfig.timeout.toInt())
            )
        )

        // Add authentication headers
        serverConfig.auth?.let { auth ->
            when (auth.authType) {
                AuthType.BASIC -> {
                    val credentials = credentialManager.getBasicAuth(auth.username, auth.password)
                    builder.defaultHeader("Authorization", "Basic $credentials")
                }
                AuthType.BEARER -> {
                    val token = credentialManager.getBearerToken(auth.token)
                    builder.defaultHeader("Authorization", "Bearer $token")
                }
                AuthType.APIKEY -> {
                    val headerName = auth.header.ifEmpty { "X-API-Key" }
                    val apiKey = credentialManager.getApiKey(auth.apiKey)
                    builder.defaultHeader(headerName, apiKey)
                }
                AuthType.NONE ->{}
            }
        }

        // Add custom headers
        serverConfig.headers.forEach { (key, value) ->
            builder.defaultHeader(key, value)
        }

        return builder.build()
    }
}