package com.openclaw.assistant.backend

import com.openclaw.assistant.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceBackendSelectorTest {
    @Test fun `openclaw wakeword falls back to http when primary gateway is not healthy`() {
        val gateway = backend("gateway", BackendType.OPENCLAW_GATEWAY, primary = true)
        val http = backend("http", BackendType.OPENCLAW_HTTP)

        val selected = VoiceBackendSelector.selectBackendId(
            voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
            backends = listOf(gateway, http),
            gatewayHealthy = false,
        )

        assertEquals("http", selected)
    }

    @Test fun `openclaw wakeword keeps healthy primary gateway`() {
        val gateway = backend("gateway", BackendType.OPENCLAW_GATEWAY, primary = true)
        val http = backend("http", BackendType.OPENCLAW_HTTP)

        val selected = VoiceBackendSelector.selectBackendId(
            voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
            backends = listOf(gateway, http),
            gatewayHealthy = true,
        )

        assertEquals("gateway", selected)
    }

    @Test fun `openclaw wakeword prefers primary http even if gateway is healthy`() {
        val gateway = backend("gateway", BackendType.OPENCLAW_GATEWAY)
        val http = backend("http", BackendType.OPENCLAW_HTTP, primary = true)

        val selected = VoiceBackendSelector.selectBackendId(
            voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
            backends = listOf(gateway, http),
            gatewayHealthy = true,
        )

        assertEquals("http", selected)
    }

    @Test fun `hermes wakeword selects hermes backend only`() {
        val openClaw = backend("openclaw", BackendType.OPENCLAW_HTTP, primary = true)
        val hermes = backend("hermes", BackendType.HERMES_API_SERVER)

        val selected = VoiceBackendSelector.selectBackendId(
            voiceTarget = SettingsRepository.VOICE_TARGET_HERMES,
            backends = listOf(openClaw, hermes),
            gatewayHealthy = false,
        )

        assertEquals("hermes", selected)
    }

    @Test fun `missing target returns null`() {
        val selected = VoiceBackendSelector.selectBackendId(
            voiceTarget = SettingsRepository.VOICE_TARGET_HERMES,
            backends = emptyList(),
            gatewayHealthy = false,
        )

        assertNull(selected)
    }

    private fun backend(id: String, type: BackendType, primary: Boolean = false) = AgentBackendConfig(
        id = id,
        displayName = id,
        type = type,
        enabled = true,
        isPrimary = primary,
        baseUrl = "http://$id.test",
        host = "$id.test",
        port = 1234,
    )
}
