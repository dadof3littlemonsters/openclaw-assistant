package com.openclaw.assistant.backend

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HermesLiveLocalTest {

    @Test
    fun localHermesRunsApiConnectsAndReplies() = runBlocking {
        val apiKey = System.getenv("API_SERVER_KEY")
            ?: System.getenv("HERMES_LOCAL_API_KEY")
            ?: System.getenv("HERMES_API_SERVER_KEY")
        val client = HermesApiServerClient(
            AgentBackendConfig(
                id = "local-hermes-live-test",
                displayName = "Local Hermes",
                type = BackendType.HERMES_API_SERVER,
                baseUrl = "http://127.0.0.1:8642",
                apiKeyOrToken = apiKey,
                modelName = "default",
                useRunsApi = true,
                useStreaming = true,
            ),
        )

        val connection = client.testConnection()
        assumeTrue("Local Hermes is not reachable: ${connection.message}", connection.ok)

        val reply = StringBuilder()
        withTimeout(60_000L) {
            client.sendMessage(
                messages = listOf(AgentMessage.user("Setup check: reply with a short confirmation.")),
                options = AgentSendOptions(sessionId = "agent-voice-local-live-test", stream = true),
            ).collect { event ->
                when (event) {
                    is AgentEvent.TokenDelta -> reply.append(event.text)
                    is AgentEvent.MessageDelta -> reply.append(event.text)
                    is AgentEvent.Completed -> if (reply.isEmpty()) reply.append(event.finalText)
                    is AgentEvent.Error -> assumeTrue("Local Hermes did not complete a run: ${event.message}", false)
                    else -> Unit
                }
            }
        }

        assertTrue("Hermes returned an empty setup-check reply", reply.toString().isNotBlank())
    }

    @Test
    fun localHermesModelCatalogReturnsAtLeastOneModel() = runBlocking {
        val apiKey = System.getenv("API_SERVER_KEY")
            ?: System.getenv("HERMES_LOCAL_API_KEY")
            ?: System.getenv("HERMES_API_SERVER_KEY")
        assumeTrue("Local Hermes API key is not available", !apiKey.isNullOrBlank())

        val catalog = HermesConfigApi().fetchCatalog(
            AgentBackendConfig(
                id = "local-hermes-catalog-live-test",
                displayName = "Local Hermes",
                type = BackendType.HERMES_API_SERVER,
                baseUrl = "http://127.0.0.1:8642",
                apiKeyOrToken = apiKey,
                modelName = "default",
            ),
        )

        assertTrue("Local Hermes model catalog was empty", catalog.models.isNotEmpty())
    }
}
