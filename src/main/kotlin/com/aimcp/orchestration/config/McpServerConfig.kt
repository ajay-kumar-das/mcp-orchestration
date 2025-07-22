package com.aimcp.orchestration.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "mcp")
data class McpServerConfig(
    var servers: Map<String, McpServerDefinition> = emptyMap(),
    var connectionTimeout: Long = 30000,
    var readTimeout: Long = 60000,
    var retryAttempts: Int = 3,
    var healthCheckInterval: Long = 60000,
    var autoDiscoveryEnabled: Boolean = true
)

data class McpServerDefinition(
    var name: String = "",
    var baseUrl: String = "",
    var description: String = "",
    var timeout: Long = 30000,
    var headers: Map<String, String> = emptyMap(),
    var enabled: Boolean = true,
    var priority: Int = 1,
    var auth: AuthConfig? = null,
    // Capabilities will be auto-discovered, not configured
    var lastHealthCheck: Long = 0,
    var isHealthy: Boolean = false
)

data class AuthConfig(
    var authType: AuthType = AuthType.NONE, // none, basic, bearer, apikey
    var username: String = "",
    var password: String = "",
    var token: String = "",
    var apiKey: String = "",
    var header: String = ""
)

enum class AuthType{
    NONE, BASIC, BEARER, APIKEY
}