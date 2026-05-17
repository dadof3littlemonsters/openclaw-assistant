package com.openclaw.assistant.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Adapter wrappers exposing the existing OpenClaw clients through [AgentClient].
 *
 * The existing OpenClaw Gateway and HTTP code paths are deeply integrated with the
 * UI, Voice Overlay, HotwordService, and node-capability stack, so they are NOT
 * routed through this interface today. These adapters exist so the rest of the
 * Agent Voice surface (Settings UI, Backend list, connection test) can treat all
 * backends uniformly.
 *
 * `sendMessage` deliberately throws — callers that target an OpenClaw backend
 * must continue to drive the legacy pipeline. Tests and the Mobile Bridge never
 * need this code path. When the UI migration lands, wire `sendMessage` to the
 * existing [com.openclaw.assistant.gateway.GatewaySession] /
 * [com.openclaw.assistant.api.OpenClawClient] here without breaking any caller.
 */
class OpenClawGatewayAdapter(override val config: AgentBackendConfig) : AgentClient {
    override suspend fun testConnection(): ConnectionTestResult {
        val host = config.host?.trim().orEmpty()
        val port = config.port ?: 0
        return if (host.isNotEmpty() && port in 1..65535) {
            ConnectionTestResult(true, "Configured ($host:$port)")
        } else {
            ConnectionTestResult(false, "Missing host/port")
        }
    }
    override fun sendMessage(messages: List<AgentMessage>, options: AgentSendOptions): Flow<AgentEvent> = flow {
        emit(AgentEvent.Error("OpenClaw Gateway uses the native voice pipeline, not AgentClient.sendMessage"))
    }
}

class OpenClawHttpAdapter(override val config: AgentBackendConfig) : AgentClient {
    override suspend fun testConnection(): ConnectionTestResult {
        val url = config.baseUrl?.trim().orEmpty()
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            ConnectionTestResult(true, "Configured")
        } else {
            ConnectionTestResult(false, "Invalid baseUrl")
        }
    }
    override fun sendMessage(messages: List<AgentMessage>, options: AgentSendOptions): Flow<AgentEvent> = flow {
        emit(AgentEvent.Error("OpenClaw HTTP uses ChatController, not AgentClient.sendMessage"))
    }
}
