// controller/OrchestrationController.kt
package com.aimcp.orchestration.controller

import com.aimcp.orchestration.model.*
import com.aimcp.orchestration.service.AiOrchestratorService
import com.aimcp.orchestration.service.ContextManagerService
import com.aimcp.orchestration.service.McpCoordinatorService
import kotlinx.coroutines.reactor.mono
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.util.*

@RestController
@RequestMapping("/api/v1/orchestration")
@CrossOrigin(origins = ["*"])
class OrchestrationController(
    private val aiOrchestratorService: AiOrchestratorService,
    private val contextManagerService: ContextManagerService,
    private val mcpCoordinatorService: McpCoordinatorService
) {

    @PostMapping("/process")
    fun processRequest(@RequestBody request: OrchestrationRequest): Mono<ResponseEntity<OrchestrationResponse>> {
        return mono {
            try {
                val response = aiOrchestratorService.processRequest(request)
                ResponseEntity.ok(response)
            } catch (e: Exception) {
                val errorResponse = OrchestrationResponse(
                    requestId = UUID.randomUUID().toString(),
                    sessionId = request.sessionId,
                    status = "error",
                    response = "Failed to process request: ${e.message}",
                    executionFlow = emptyList(),
                    metadata = ResponseMetadata(
                        totalDuration = 0,
                        stepsExecuted = 0,
                        serversUsed = emptyList(),
                        toolsUsed = emptyList()
                    )
                )
                ResponseEntity.badRequest().body(errorResponse)
            }
        }
    }

    @GetMapping("/tools")
    fun getAvailableTools(): Mono<ResponseEntity<Map<String, Any>>> {
        return mono {
            try {
                val tools = mcpCoordinatorService.getAvailableTools()
                val response = mapOf(
                    "status" to "success",
                    "tools" to tools,
                    "count" to tools.size,
                    "servers" to tools.groupBy { it.serverName }.keys.toList()
                )
                ResponseEntity.ok(response)
            } catch (e: Exception) {
                val errorResponse = mapOf(
                    "status" to "error",
                    "message" to "Failed to retrieve tools: ${e.message}",
                    "tools" to emptyList<McpTool>(),
                    "count" to 0
                )
                ResponseEntity.badRequest().body(errorResponse)
            }
        }
    }

    @GetMapping("/tools/{serverName}")
    fun getServerTools(@PathVariable serverName: String): Mono<ResponseEntity<Map<String, Any>>> {
        return mono {
            try {
                val allTools = mcpCoordinatorService.getAvailableTools()
                val serverTools = allTools.filter { it.serverName == serverName }
                val serverCapabilities = mcpCoordinatorService.getServerCapabilities(serverName)
                val serverHealth = mcpCoordinatorService.getServerHealth()[serverName]

                val response = mapOf<String, Any>(
                    "status" to "success",
                    "serverName" to serverName,
                    "tools" to serverTools,
                    "count" to serverTools.size,
                    "capabilities" to (serverCapabilities ?: emptyMap<String, Any>()),
                    "health" to (serverHealth ?: mapOf<String, Any>(
                        "isHealthy" to false,
                        "lastChecked" to 0,
                        "capabilities" to emptyList<String>()
                    ))
                )
                ResponseEntity.ok(response)
            } catch (e: Exception) {
                val errorResponse = mapOf<String, Any>(
                    "status" to "error",
                    "message" to "Failed to retrieve tools for server $serverName: ${e.message}",
                    "serverName" to serverName,
                    "tools" to emptyList<McpTool>(),
                    "count" to 0,
                    "capabilities" to emptyMap<String, Any>(),
                    "health" to mapOf<String, Any>(
                        "isHealthy" to false,
                        "lastChecked" to 0,
                        "capabilities" to emptyList<String>()
                    )
                )
                ResponseEntity.badRequest().body(errorResponse)
            }
        }
    }

    @PostMapping("/configure")
    fun configureSession(
        @RequestParam sessionId: String,
        @RequestBody preferences: OrchestrationPreferences
    ): Mono<ResponseEntity<Map<String, Any>>> {
        return mono {
            try {
                val context = contextManagerService.getOrCreateContext(sessionId)
                context.metadata["preferences"] = preferences
                contextManagerService.updateContext(context)

                val response = mapOf<String, Any>(
                    "status" to "success",
                    "sessionId" to sessionId,
                    "message" to "Session configured successfully",
                    "preferences" to preferences
                )
                ResponseEntity.ok(response)
            } catch (e: Exception) {
                val errorResponse = mapOf<String, Any>(
                    "status" to "error",
                    "message" to "Failed to configure session: ${e.message}",
                    "sessionId" to sessionId
                )
                ResponseEntity.badRequest().body(errorResponse)
            }
        }
    }

    @GetMapping("/health")
    fun healthCheck(): Mono<ResponseEntity<Map<String, Any>>> {
        return mono {
            val response = mapOf<String, Any>(
                "status" to "healthy",
                "timestamp" to System.currentTimeMillis(),
                "service" to "AI-MCP Orchestration Platform",
                "version" to "1.0.0"
            )
            ResponseEntity.ok(response)
        }
    }

    @GetMapping("/status")
    fun getSystemStatus(): Mono<ResponseEntity<Map<String, Any>>> {
        return mono {
            try {
                val tools = mcpCoordinatorService.getAvailableTools()
                val serverHealth = mcpCoordinatorService.getServerHealth()
                val contextMetrics = contextManagerService.getContextMetrics()
                val orchestrationMetrics = aiOrchestratorService.getOrchestrationMetrics()

                val serverStatus = serverHealth.mapValues { (serverName, health) ->
                    val serverTools = tools.filter { it.serverName == serverName }
                    mapOf<String, Any>(
                        "available" to health.isHealthy,
                        "toolCount" to serverTools.size,
                        "tools" to serverTools.map { it.name },
                        "lastHealthCheck" to health.lastChecked,
                        "capabilities" to health.capabilities
                    )
                }

                val response = mapOf<String, Any>(
                    "status" to "operational",
                    "timestamp" to System.currentTimeMillis(),
                    "servers" to serverStatus,
                    "totalTools" to tools.size,
                    "totalServers" to serverStatus.size,
                    "healthyServers" to serverHealth.count { it.value.isHealthy },
                    "context" to mapOf<String, Any>(
                        "totalSessions" to contextMetrics.totalSessions,
                        "activeSessions" to contextMetrics.activeSessions,
                        "totalMessages" to contextMetrics.totalMessages,
                        "averageSessionAge" to contextMetrics.averageSessionAge
                    ),
                    "orchestration" to mapOf<String, Any>(
                        "activeRequests" to orchestrationMetrics.activeRequests,
                        "maxConcurrentRequests" to orchestrationMetrics.maxConcurrentRequests,
                        "queueLength" to orchestrationMetrics.queueLength
                    )
                )
                ResponseEntity.ok(response)
            } catch (e: Exception) {
                val errorResponse = mapOf<String, Any>(
                    "status" to "error",
                    "message" to "Failed to get system status: ${e.message}",
                    "timestamp" to System.currentTimeMillis()
                )
                ResponseEntity.badRequest().body(errorResponse)
            }
        }
    }

    @DeleteMapping("/session/{sessionId}")
    fun clearSession(@PathVariable sessionId: String): Mono<ResponseEntity<Map<String, Any>>> {
        return mono {
            try {
                contextManagerService.clearContext(sessionId)
                val response = mapOf<String, Any>(
                    "status" to "success",
                    "message" to "Session cleared successfully",
                    "sessionId" to sessionId
                )
                ResponseEntity.ok(response)
            } catch (e: Exception) {
                val errorResponse = mapOf<String, Any>(
                    "status" to "error",
                    "message" to "Failed to clear session: ${e.message}",
                    "sessionId" to sessionId
                )
                ResponseEntity.badRequest().body(errorResponse)
            }
        }
    }

    @GetMapping("/sessions")
    fun getAllSessions(): Mono<ResponseEntity<Map<String, Any>>> {
        return mono {
            try {
                val sessions = contextManagerService.getAllSessionInfos()
                val response = mapOf<String, Any>(
                    "status" to "success",
                    "sessions" to sessions,
                    "count" to sessions.size,
                    "activeSessions" to sessions.count { it.isActive }
                )
                ResponseEntity.ok(response)
            } catch (e: Exception) {
                val errorResponse = mapOf<String, Any>(
                    "status" to "error",
                    "message" to "Failed to retrieve sessions: ${e.message}",
                    "sessions" to emptyList<Any>(),
                    "count" to 0
                )
                ResponseEntity.badRequest().body(errorResponse)
            }
        }
    }

    @GetMapping("/session/{sessionId}")
    fun getSessionInfo(@PathVariable sessionId: String): Mono<ResponseEntity<Map<String, Any>>> {
        return mono {
            try {
                val sessionInfo = contextManagerService.getSessionInfo(sessionId)
                if (sessionInfo != null) {
                    val response = mapOf<String, Any>(
                        "status" to "success",
                        "session" to sessionInfo
                    )
                    ResponseEntity.ok(response)
                } else {
                    val response = mapOf<String, Any>(
                        "status" to "not_found",
                        "message" to "Session not found",
                        "sessionId" to sessionId
                    )
                    ResponseEntity.notFound().build<Map<String, Any>>()
                }
            } catch (e: Exception) {
                val errorResponse = mapOf<String, Any>(
                    "status" to "error",
                    "message" to "Failed to retrieve session info: ${e.message}",
                    "sessionId" to sessionId
                )
                ResponseEntity.badRequest().body(errorResponse)
            }
        }
    }

    @PostMapping("/servers/{serverName}/test")
    fun testServerConnection(@PathVariable serverName: String): Mono<ResponseEntity<Map<String, Any>>> {
        return mono {
            try {
                val isHealthy = mcpCoordinatorService.testServerConnection(serverName)
                val response = mapOf<String, Any>(
                    "status" to "success",
                    "serverName" to serverName,
                    "isHealthy" to isHealthy,
                    "message" to if (isHealthy) "Server is healthy" else "Server is unhealthy",
                    "timestamp" to System.currentTimeMillis()
                )
                ResponseEntity.ok(response)
            } catch (e: Exception) {
                val errorResponse = mapOf<String, Any>(
                    "status" to "error",
                    "message" to "Failed to test server connection: ${e.message}",
                    "serverName" to serverName,
                    "isHealthy" to false
                )
                ResponseEntity.badRequest().body(errorResponse)
            }
        }
    }

    @PostMapping("/cache/invalidate")
    fun invalidateCache(@RequestParam(required = false) serverName: String?): Mono<ResponseEntity<Map<String, Any>>> {
        return mono {
            try {
                mcpCoordinatorService.invalidateToolCache(serverName)
                val response = mapOf<String, Any>(
                    "status" to "success",
                    "message" to if (serverName != null)
                        "Cache invalidated for server: $serverName"
                    else
                        "All cache invalidated",
                    "timestamp" to System.currentTimeMillis()
                )
                ResponseEntity.ok(response)
            } catch (e: Exception) {
                val errorResponse = mapOf<String, Any>(
                    "status" to "error",
                    "message" to "Failed to invalidate cache: ${e.message}",
                    "timestamp" to System.currentTimeMillis()
                )
                ResponseEntity.badRequest().body(errorResponse)
            }
        }
    }
}