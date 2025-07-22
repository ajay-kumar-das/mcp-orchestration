package com.aimcp.orchestration.model

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class ConversationContext(
    val sessionId: String,
    val messages: MutableList<ContextMessage> = mutableListOf(),
    val availableTools: MutableList<McpTool> = mutableListOf(),
    val executionHistory: MutableList<ExecutionStep> = mutableListOf(),
    val userPreferences: Map<String, Any> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    var lastActivity: Instant = Instant.now(),
    val metadata: MutableMap<String, Any> = ConcurrentHashMap()
)

data class ContextMessage(
    val role: String, // user, assistant, system
    val content: String,
    val timestamp: Instant = Instant.now(),
    val metadata: Map<String, Any> = emptyMap()
)
