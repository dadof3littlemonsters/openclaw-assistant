package com.openclaw.assistant.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendManagerTest {

    private fun repo(): BackendRepository = BackendRepository(InMemorySharedPreferences())

    @Test fun `primaryClient returns Hermes when Hermes is primary`() {
        val r = repo()
        r.upsert(AgentBackendConfig(id = "h", displayName = "H", type = BackendType.HERMES_API_SERVER, baseUrl = "http://h:8642", apiKeyOrToken = "k"))
        // BackendManager normally a singleton; we exercise repo+factory directly to avoid Android Context.
        val primary = r.primary
        assertNotNull(primary)
        val client = AgentClientFactory.create(primary!!)
        assertTrue(client is HermesApiServerClient)
    }

    @Test fun `disabling primary with another enabled backend reassigns primary`() {
        val r = repo()
        r.upsert(AgentBackendConfig(id = "a", displayName = "A", type = BackendType.HERMES_API_SERVER, baseUrl = "http://a"))
        r.upsert(AgentBackendConfig(id = "b", displayName = "B", type = BackendType.OPENCLAW_HTTP, baseUrl = "http://b"))
        // a was primary; disable it
        r.setEnabled("a", false)
        assertEquals("b", r.primary?.id)
    }

    @Test fun `chatTargets only enabled`() {
        val r = repo()
        r.upsert(AgentBackendConfig(id = "a", displayName = "A", type = BackendType.HERMES_API_SERVER, baseUrl = "http://a"))
        r.upsert(AgentBackendConfig(id = "b", displayName = "B", type = BackendType.OPENCLAW_HTTP, baseUrl = "http://b"))
        r.setEnabled("b", false)
        val enabled = r.backends.value.filter { it.enabled }
        assertEquals(listOf("A"), enabled.map { it.displayName })
    }
}
