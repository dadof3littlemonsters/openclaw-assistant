package com.openclaw.assistant.backend

import kotlinx.serialization.Serializable
import java.util.UUID

enum class HermesMode { CHAT_COMPLETIONS, RUNS_API }

@Serializable
data class AgentBackendConfig(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val type: BackendType,
    val enabled: Boolean = true,
    val isPrimary: Boolean = false,
    val baseUrl: String? = null,
    val apiKeyOrToken: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val useTls: Boolean = false,
    val modelName: String? = null,
    val useRunsApi: Boolean = true,
    val useStreaming: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * Additional endpoints to race alongside [baseUrl] at connect time.
     * Additional routes such as LAN, VPN, and public URLs. The client tries all
     * candidates in parallel on connect and uses the first reachable route.
     */
    val secondaryUrls: List<String> = emptyList(),
    /**
     * Hermes Dashboard endpoint used by the Terminal tab. Hermes exposes the
     * PTY bridge at `/api/pty` on `hermes dashboard --tui`, not on the normal
     * API server port.
     */
    val terminalUrl: String? = null,
    val terminalSessionToken: String? = null,
    /** Optional cross-backend agent/profile label selected by the user. */
    val agentContextName: String? = null,
    /** Optional model/personality/profile hint shown in shared Agent Context UI. */
    val agentContextDetail: String? = null,
    /** Optional preferred endpoint role, such as lan, vpn, or public. */
    val preferredEndpointRole: String? = null,
) {
    val hermesMode: HermesMode
        get() = if (useRunsApi) HermesMode.RUNS_API else HermesMode.CHAT_COMPLETIONS
}
