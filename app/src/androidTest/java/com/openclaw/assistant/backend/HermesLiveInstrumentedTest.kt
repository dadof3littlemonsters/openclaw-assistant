package com.openclaw.assistant.backend

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HermesLiveInstrumentedTest {

    @Test
    fun localHermesRunsApiConnectsAndReplies() = runBlocking {
        val client = HermesApiServerClient(
            AgentBackendConfig(
                id = "instrumented-hermes-local",
                displayName = "Local Hermes",
                type = BackendType.HERMES_API_SERVER,
                baseUrl = "http://127.0.0.1:8642",
                modelName = "hermes-agent",
                useRunsApi = true,
                useStreaming = true,
            ),
        )

        val connection = client.testConnection()
        assumeTrue("Local Hermes is not reachable from the device: ${connection.message}", connection.ok)

        val reply = StringBuilder()
        withTimeout(60_000L) {
            client.sendMessage(
                messages = listOf(AgentMessage.user("Setup check: reply with a short confirmation.")),
                options = AgentSendOptions(sessionId = "agent-voice-instrumented-setup-check", stream = true),
            ).collect { event ->
                when (event) {
                    is AgentEvent.TokenDelta -> reply.append(event.text)
                    is AgentEvent.MessageDelta -> reply.append(event.text)
                    is AgentEvent.Completed -> if (reply.isEmpty()) reply.append(event.finalText)
                    is AgentEvent.Error -> error(event.message)
                    else -> Unit
                }
            }
        }

        assertTrue("Hermes returned an empty setup-check reply", reply.toString().isNotBlank())
    }
}
