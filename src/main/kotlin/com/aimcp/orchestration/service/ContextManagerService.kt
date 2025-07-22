// service/ContextManagerService.kt
package com.aimcp.orchestration.service

import com.aimcp.orchestration.config.ContextConfig
import com.aimcp.orchestration.model.ConversationContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Service
class ContextManagerService(
    private val contextConfig: ContextConfig
) {

    private val logger = LoggerFactory.getLogger(ContextManagerService::class.java)
    private val contexts = ConcurrentHashMap<String, ConversationContext>()
    private val activeSessionsCount = AtomicInteger(0)

    fun getOrCreateContext(sessionId: String): ConversationContext {
        return contexts.computeIfAbsent(sessionId) { id ->
            if (contexts.size >= contextConfig.maxSessions) {
                cleanupOldestSessions()
            }

            activeSessionsCount.incrementAndGet()
            logger.debug("Created new context for session: $id")

            ConversationContext(sessionId = id)
        }.also { context ->
            context.lastActivity = Instant.now()

            // Trim message history if context exceeds max size
            if (context.messages.size > contextConfig.maxHistorySize) {
                val excess = context.messages.size - contextConfig.maxHistorySize
                repeat(excess) {
                    context.messages.removeAt(0)
                }
                logger.debug("Trimmed $excess old messages from session: $sessionId")
            }
        }
    }

    fun updateContext(context: ConversationContext) {
        context.lastActivity = Instant.now()
        contexts[context.sessionId] = context
        logger.trace("Updated context for session: ${context.sessionId}")
    }

    fun clearContext(sessionId: String) {
        contexts.remove(sessionId)?.let {
            activeSessionsCount.decrementAndGet()
            logger.debug("Cleared context for session: $sessionId")
        }
    }

    fun getContextMetrics(): ContextMetrics {
        val now = Instant.now()
        val activeSessions = contexts.count {
            now.toEpochMilli() - it.value.lastActivity.toEpochMilli() < contextConfig.sessionTimeout
        }

        return ContextMetrics(
            totalSessions = contexts.size,
            activeSessions = activeSessions,
            totalMessages = contexts.values.sumOf { it.messages.size },
            averageSessionAge = if (contexts.isNotEmpty()) {
                contexts.values.map { now.toEpochMilli() - it.createdAt.toEpochMilli() }.average()
            } else 0.0
        )
    }

    @Scheduled(fixedDelayString = "#{@contextConfig.cleanupInterval}")
    fun cleanupExpiredContexts() {
        val cutoff = Instant.now().minusMillis(contextConfig.sessionTimeout)
        val expiredSessions = contexts.entries
            .filter { it.value.lastActivity.isBefore(cutoff) }
            .map { it.key }

        var cleanedCount = 0
        expiredSessions.forEach { sessionId ->
            contexts.remove(sessionId)?.let {
                activeSessionsCount.decrementAndGet()
                cleanedCount++
            }
        }

        if (cleanedCount > 0) {
            logger.info("Cleaned up $cleanedCount expired sessions. Active sessions: ${activeSessionsCount.get()}")
        }
    }

    private fun cleanupOldestSessions() {
        val sessionsToRemove = (contexts.size - contextConfig.maxSessions + 1).coerceAtLeast(1)

        val oldestSessions = contexts.entries
            .sortedBy { it.value.lastActivity }
            .take(sessionsToRemove)
            .map { it.key }

        var removedCount = 0
        oldestSessions.forEach { sessionId ->
            contexts.remove(sessionId)?.let {
                activeSessionsCount.decrementAndGet()
                removedCount++
            }
        }

        logger.warn("Removed $removedCount oldest sessions to make room for new sessions")
    }

    fun getSessionInfo(sessionId: String): SessionInfo? {
        val context = contexts[sessionId] ?: return null
        val now = Instant.now()

        return SessionInfo(
            sessionId = sessionId,
            createdAt = context.createdAt,
            lastActivity = context.lastActivity,
            messageCount = context.messages.size,
            toolsUsed = context.availableTools.map { it.name }.distinct(),
            serversUsed = context.availableTools.map { it.serverName }.distinct(),
            isActive = now.toEpochMilli() - context.lastActivity.toEpochMilli() < contextConfig.sessionTimeout
        )
    }

    fun getAllSessionInfos(): List<SessionInfo> {
        return contexts.keys.mapNotNull { getSessionInfo(it) }
    }
}

data class ContextMetrics(
    val totalSessions: Int,
    val activeSessions: Int,
    val totalMessages: Int,
    val averageSessionAge: Double
)

data class SessionInfo(
    val sessionId: String,
    val createdAt: Instant,
    val lastActivity: Instant,
    val messageCount: Int,
    val toolsUsed: List<String>,
    val serversUsed: List<String>,
    val isActive: Boolean
)