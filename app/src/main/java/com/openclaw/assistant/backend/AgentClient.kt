package com.openclaw.assistant.backend

import kotlinx.coroutines.flow.Flow

data class ConnectionTestResult(
    val ok: Boolean,
    val message: String,
    val latencyMs: Long? = null,
)

interface AgentClient {
    val config: AgentBackendConfig
    suspend fun testConnection(): ConnectionTestResult
    fun sendMessage(
        messages: List<AgentMessage>,
        options: AgentSendOptions = AgentSendOptions(),
    ): Flow<AgentEvent>
    suspend fun stopCurrentRun(): Boolean = false
}
