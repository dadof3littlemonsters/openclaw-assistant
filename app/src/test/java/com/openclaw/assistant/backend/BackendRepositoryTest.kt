package com.openclaw.assistant.backend

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackendRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var repo: BackendRepository

    @Before fun setUp() {
        prefs = InMemorySharedPreferences()
        repo = BackendRepository(prefs)
    }

    @Test fun `first inserted backend becomes primary`() {
        repo.upsert(AgentBackendConfig(displayName = "Hermes", type = BackendType.HERMES_API_SERVER, baseUrl = "http://h:8642"))
        assertEquals(1, repo.backends.value.size)
        assertTrue(repo.backends.value.single().isPrimary)
        assertEquals("Hermes", repo.primary?.displayName)
    }

    @Test fun `setPrimary moves the flag and keeps invariant`() {
        repo.upsert(AgentBackendConfig(id = "a", displayName = "A", type = BackendType.HERMES_API_SERVER, baseUrl = "http://a", isPrimary = true))
        repo.upsert(AgentBackendConfig(id = "b", displayName = "B", type = BackendType.OPENCLAW_HTTP, baseUrl = "http://b"))
        repo.setPrimary("b")
        val list = repo.backends.value
        assertEquals(1, list.count { it.isPrimary })
        assertEquals("b", list.single { it.isPrimary }.id)
    }

    @Test fun `delete reassigns primary to the next enabled backend`() {
        repo.upsert(AgentBackendConfig(id = "a", displayName = "A", type = BackendType.HERMES_API_SERVER, baseUrl = "http://a", isPrimary = true))
        repo.upsert(AgentBackendConfig(id = "b", displayName = "B", type = BackendType.OPENCLAW_HTTP, baseUrl = "http://b"))
        repo.delete("a")
        assertEquals("b", repo.primary?.id)
    }

    @Test fun `disabling primary triggers reassignment if other enabled exists`() {
        repo.upsert(AgentBackendConfig(id = "a", displayName = "A", type = BackendType.HERMES_API_SERVER, baseUrl = "http://a"))
        repo.upsert(AgentBackendConfig(id = "b", displayName = "B", type = BackendType.OPENCLAW_HTTP, baseUrl = "http://b"))
        // a is primary. disable it.
        repo.setEnabled("a", false)
        assertEquals("b", repo.primary?.id)
    }

    @Test fun `last backend remaining is forced primary`() {
        repo.upsert(AgentBackendConfig(id = "a", displayName = "A", type = BackendType.HERMES_API_SERVER, baseUrl = "http://a"))
        assertTrue(repo.backends.value.single().isPrimary)
    }
}
