package com.openclaw.assistant.backend

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesConfigApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `fetchCatalog reads current Hermes api server v1 models`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "object": "list",
                      "data": [
                        {
                          "id": "hermes-agent",
                          "object": "model",
                          "owned_by": "hermes"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val catalog = HermesConfigApi().fetchCatalog(config())

        assertEquals(listOf("hermes-agent"), catalog.models.map { it.id })
        assertEquals("hermes", catalog.models.single().description)
        assertEquals("/api/config", server.takeRequest().path)
        assertEquals("/api/available-models", server.takeRequest().path)
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test fun `fetchCatalog keeps legacy Hermes setup payload models`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"model":"current-hermes","provider":"openrouter"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "models": ["a", {"model": "b", "description": "legacy"}],
                      "providers": ["openrouter"]
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(MockResponse().setResponseCode(404))

        val catalog = HermesConfigApi().fetchCatalog(config())

        assertEquals(listOf("a", "b"), catalog.models.map { it.id })
        assertEquals(listOf("openrouter"), catalog.providers)
        assertEquals("current-hermes", catalog.config?.model)
    }

    @Test fun `fetchCatalog falls back to current config model when lists are unavailable`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"model":"configured-model","provider":"local"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))

        val catalog = HermesConfigApi().fetchCatalog(config())

        assertEquals(listOf("configured-model"), catalog.models.map { it.id })
        assertEquals("current", catalog.models.single().description)
    }

    @Test fun `fetchCatalog reads dashboard model options when terminal is paired`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"object":"list","data":[{"id":"hermes-agent","owned_by":"hermes"}]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "model": "anthropic/claude-sonnet-4.6",
                      "provider": "openrouter",
                      "providers": [
                        {
                          "name": "OpenRouter",
                          "models": [
                            {"id": "anthropic/claude-sonnet-4.6", "label": "Claude Sonnet"},
                            {"id": "openrouter/auto"}
                          ]
                        },
                        {
                          "name": "OpenAI Codex",
                          "models": ["gpt-5.5"]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val catalog = HermesConfigApi().fetchCatalog(
            config().copy(
                terminalUrl = server.url("/").toString().trimEnd('/'),
                terminalSessionToken = "session-token",
            ),
        )

        assertEquals(
            listOf("anthropic/claude-sonnet-4.6", "openrouter/auto", "gpt-5.5", "hermes-agent"),
            catalog.models.map { it.id },
        )
        assertEquals("anthropic/claude-sonnet-4.6", catalog.config?.model)
        assertEquals("openrouter", catalog.config?.provider)
        assertEquals(listOf("OpenRouter", "OpenAI Codex"), catalog.providers)
        assertEquals("/api/config", server.takeRequest().path)
        assertEquals("/api/available-models", server.takeRequest().path)
        assertEquals("/v1/models", server.takeRequest().path)
        val dashboardRequest = server.takeRequest()
        assertEquals("/api/model/options", dashboardRequest.path)
        assertEquals("session-token", dashboardRequest.getHeader("X-Hermes-Session-Token"))
    }

    private fun config() = AgentBackendConfig(
        id = "hermes-config-api-test",
        displayName = "Hermes",
        type = BackendType.HERMES_API_SERVER,
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKeyOrToken = "test-token",
    )
}
