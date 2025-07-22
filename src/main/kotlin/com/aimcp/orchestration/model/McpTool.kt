package com.aimcp.orchestration.model

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>,
    val serverName: String
)

data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: Map<String, Any>? = null
)

data class McpResponse(
    val jsonrpc: String,
    val id: String,
    val result: Any? = null,
    val error: McpError? = null
)

data class McpError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

data class McpToolCall(
    val serverName: String,
    val toolName: String,
    val arguments: Map<String, Any>
)
