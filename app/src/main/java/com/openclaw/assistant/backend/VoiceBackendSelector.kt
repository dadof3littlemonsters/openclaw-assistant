package com.openclaw.assistant.backend

import com.openclaw.assistant.data.SettingsRepository

object VoiceBackendSelector {
    fun selectBackendId(
        voiceTarget: String,
        backends: List<AgentBackendConfig>,
        gatewayHealthy: Boolean,
    ): String? {
        val enabled = backends.filter { it.enabled }
        return if (voiceTarget == SettingsRepository.VOICE_TARGET_OPENCLAW) {
            selectOpenClawBackend(enabled, gatewayHealthy)?.id
        } else {
            enabled.firstOrNull {
                it.isPrimary && it.type == BackendType.HERMES_API_SERVER
            }?.id
                ?: enabled.firstOrNull { it.type == BackendType.HERMES_API_SERVER }?.id
        }
    }

    private fun selectOpenClawBackend(
        enabled: List<AgentBackendConfig>,
        gatewayHealthy: Boolean,
    ): AgentBackendConfig? {
        val primaryOpenClaw = enabled.firstOrNull {
            it.isPrimary && (it.type == BackendType.OPENCLAW_GATEWAY || it.type == BackendType.OPENCLAW_HTTP)
        }
        if (primaryOpenClaw?.type == BackendType.OPENCLAW_HTTP) return primaryOpenClaw
        if (primaryOpenClaw?.type == BackendType.OPENCLAW_GATEWAY && gatewayHealthy) return primaryOpenClaw

        val http = enabled.firstOrNull { it.type == BackendType.OPENCLAW_HTTP }
        if (http != null) return http

        val gateway = enabled.firstOrNull { it.type == BackendType.OPENCLAW_GATEWAY }
        return if (gatewayHealthy) gateway else primaryOpenClaw ?: gateway
    }
}
