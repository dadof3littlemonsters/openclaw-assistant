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

    private fun uri(scheme: String, u: List<String>, params: Map<String, String?>): Uri = mockk {
        every { this@mockk.scheme } returns scheme
        every { getQueryParameters("u") } returns u
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
        val p = parsePairingUri(u)!!
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
        val p = parsePairingUri(u)!!
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
        val p = parsePairingUri(u)!!
        assertEquals(listOf("https://ok"), p.secondaryUrls)
    }
}
