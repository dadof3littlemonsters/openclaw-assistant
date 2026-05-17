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
    val useRunsApi: Boolean = false,
    val useStreaming: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * Additional endpoints to race alongside [baseUrl] at connect time.
     * Inspired by Hermes-Relay's "LAN + Tailscale + public URLs" model: the
     * client tries all candidates in parallel on every connect and on every
     * network change, using whichever responds first.
     */
    val secondaryUrls: List<String> = emptyList(),
) {
    val hermesMode: HermesMode
        get() = if (useRunsApi) HermesMode.RUNS_API else HermesMode.CHAT_COMPLETIONS
}
