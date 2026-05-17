package com.openclaw.assistant.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HermesUrlTest {
    @Test fun `appends v1 when missing`() {
        assertEquals("http://host:8642/v1", HermesUrl.normalizeBase("http://host:8642"))
    }
    @Test fun `keeps v1 when already present`() {
        assertEquals("http://host:8642/v1", HermesUrl.normalizeBase("http://host:8642/v1"))
    }
    @Test fun `strips trailing slash`() {
        assertEquals("http://host:8642/v1", HermesUrl.normalizeBase("http://host:8642/v1/"))
        assertEquals("http://host:8642/v1", HermesUrl.normalizeBase("http://host:8642/"))
    }
    @Test fun `supports https and path prefix`() {
        assertEquals("https://host/openai/v1", HermesUrl.normalizeBase("https://host/openai/v1"))
    }
    @Test fun `rejects bare host`() {
        assertThrows(IllegalArgumentException::class.java) { HermesUrl.normalizeBase("host:8642") }
    }
    @Test fun `derives chat and models urls`() {
        assertEquals("http://h:1/v1/chat/completions", HermesUrl.chatCompletionsUrl("http://h:1"))
        assertEquals("http://h:1/v1/models", HermesUrl.modelsUrl("http://h:1/v1"))
        assertEquals("http://h:1/v1/runs/abc/stop", HermesUrl.runStopUrl("http://h:1", "abc"))
        assertEquals("http://h:1/health", HermesUrl.rootHealthUrl("http://h:1/v1"))
    }
}
