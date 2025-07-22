// config/OrchestrationConfig.kt
package com.aimcp.orchestration.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "orchestration")
data class OrchestrationConfig(
    var defaultMaxSteps: Int = 10,
    var defaultTimeout: Long = 30000,
    var maxConcurrentRequests: Int = 100,
    var requestQueueSize: Int = 1000
)

@Component
@ConfigurationProperties(prefix = "context")
data class ContextConfig(
    var sessionTimeout: Long = 3600000, // 1 hour
    var maxSessions: Int = 10000,
    var cleanupInterval: Long = 300000, // 5 minutes
    var maxHistorySize: Int = 100
)