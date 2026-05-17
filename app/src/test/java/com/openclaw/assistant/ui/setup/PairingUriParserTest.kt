package com.openclaw.assistant.ui.setup

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `android.net.Uri` is a stub in JVM unit tests, so we mock it. The parser
 * itself is pure Kotlin and just reads the scheme + `getQueryParameter*`
 * outputs, which keeps the test deterministic.
 */
class PairingUriParserTest {

    private fun uri(scheme: String, u: List<String>, params: Map<String, String?>, host: String = "hermes"): Uri = mockk {
        every { this@mockk.scheme } returns scheme
        every { this@mockk.host } returns host
        every { getQueryParameters("u") } returns u
        every { getQueryParameters("hu") } returns if (host == "setup") u else emptyList()
        params.forEach { (k, v) -> every { getQueryParameter(k) } returns v }
        every { getQueryParameter(match { it !in params.keys }) } returns null
    }

    @Test fun `rejects wrong scheme`() {
        val u = uri("https", listOf("http://h:8642"), mapOf("k" to "x"))
        assertNull(parsePairingUri(u))
    }

    @Test fun `rejects when no http url`() {
        val u = uri("agentvoice", listOf("not-a-url"), emptyMap())
        assertNull(parsePairingUri(u))
    }

    @Test fun `parses minimum valid payload`() {
        val u = uri("agentvoice", listOf("http://h:8642"), mapOf("k" to null, "m" to null, "r" to null, "s" to null, "n" to null))
        val p = parsePairingUri(u)!!.hermes!!
        assertEquals("http://h:8642", p.baseUrl)
        assertTrue(p.secondaryUrls.isEmpty())
        assertEquals("hermes-agent", p.modelName)
        assertEquals(false, p.useRunsApi)
        assertEquals(true, p.streaming)
        assertNull(p.apiKey)
    }

    @Test fun `multiple u params populate secondaryUrls`() {
        val u = uri("agentvoice",
            listOf("http://lan:8642", "http://tail:8642", "https://relay"),
            mapOf("k" to "key", "m" to "hermes-large", "r" to "1", "s" to "0", "n" to "Home"))
        val p = parsePairingUri(u)!!.hermes!!
        assertEquals("http://lan:8642", p.baseUrl)
        assertEquals(listOf("http://tail:8642", "https://relay"), p.secondaryUrls)
        assertEquals("key", p.apiKey)
        assertEquals("hermes-large", p.modelName)
        assertTrue(p.useRunsApi)
        assertEquals(false, p.streaming)
        assertEquals("Home", p.displayName)
    }

    @Test fun `non-http secondary urls are dropped`() {
        val u = uri("agentvoice",
            listOf("http://lan:8642", "ftp://bad", "https://ok"),
            emptyMap())
        val p = parsePairingUri(u)!!.hermes!!
        assertEquals(listOf("https://ok"), p.secondaryUrls)
    }

    @Test fun `combined setup uri supports Hermes urls and OpenClaw code`() {
        val u = uri(
            scheme = "agentvoice",
            host = "setup",
            u = listOf("http://tail:8642", "http://lan:8642", "http://127.0.0.1:8642"),
            params = mapOf("hm" to "hermes-agent", "hr" to "0", "hs" to "1", "oc" to "abc")
        )

        val p = parsePairingUri(u)!!
        assertEquals("abc", p.openClawSetupCode)
        val h = p.hermes!!
        assertEquals("http://tail:8642", h.baseUrl)
        assertEquals(listOf("http://lan:8642", "http://127.0.0.1:8642"), h.secondaryUrls)
    }

    @Test fun `combined setup json supports Hermes urls and OpenClaw code`() {
        val raw = """
            {
              "type": "agent_voice_setup",
              "version": 1,
              "hermes": {
                "urls": ["http://tail:8642", "http://lan:8642", "http://127.0.0.1:8642"],
                "model": "hermes-agent",
                "runs": false,
                "streaming": true
              },
              "openclaw": {
                "setupCode": "openclaw-code"
              }
            }
        """.trimIndent()

        val p = parsePairingPayload(raw)!!
        assertEquals("openclaw-code", p.openClawSetupCode)
        val h = p.hermes!!
        assertEquals("http://tail:8642", h.baseUrl)
        assertEquals(listOf("http://lan:8642", "http://127.0.0.1:8642"), h.secondaryUrls)
        assertEquals("hermes-agent", h.modelName)
        assertEquals(false, h.useRunsApi)
        assertEquals(true, h.streaming)
    }
}
