// model/OrchestrationRequest.kt
package com.aimcp.orchestration.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class OrchestrationRequest(
    val message: String,
    val sessionId: String? = null,
    val context: Map<String, Any> = emptyMap(),
    val preferences: OrchestrationPreferences = OrchestrationPreferences(),
    val timestamp: Instant = Instant.now()
)

data class OrchestrationPreferences(
    val maxSteps: Int = 10,
    val timeout: Long = 30000,
    val preferredServers: List<String> = emptyList(),
    val responseFormat: String = "detailed", // detailed, summary, raw
    val includeMetadata: Boolean = true,
    val aiProvider: String? = null,
    val maxTokens: Int = 0, // 0 means use provider default
    val temperature: Double = -1.0 // -1 means use provider default
)

data class OrchestrationResponse(
    val requestId: String,
    val sessionId: String?,
    val status: String, // success, partial, error
    val response: String,
    val executionFlow: List<ExecutionStep> = emptyList(),
    val metadata: ResponseMetadata,
    val timestamp: Instant = Instant.now()
)

data class ExecutionStep(
    val stepId: String,
    val type: String, // ai_analysis, mcp_call, synthesis
    val timestamp: Instant,
    val duration: Long,
    val serverName: String?,
    val toolName: String?,
    val input: String?,
    val output: String?,
    val success: Boolean,
    val metadata: Map<String, Any> = emptyMap()
)

data class ResponseMetadata(
    val totalDuration: Long,
    val stepsExecuted: Int,
    val serversUsed: List<String>,
    val toolsUsed: List<String>,
    val performance: Map<String, Any> = emptyMap()
)