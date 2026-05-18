package com.openclaw.assistant.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.AgentEvent
import com.openclaw.assistant.backend.AgentMessage
import com.openclaw.assistant.backend.AgentSendOptions
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.HermesApiServerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class HermesLiveProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:8642"
        val useRuns = intent.getBooleanExtra(EXTRA_RUNS, true)
        Log.i(TAG, "START baseUrl=$baseUrl runs=$useRuns")
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { runProbe(baseUrl, useRuns) }
                Log.i(TAG, "PASS reply=${reply.take(240)}")
            } catch (e: Throwable) {
                Log.e(TAG, "FAIL ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun runProbe(baseUrl: String, useRuns: Boolean): String {
        val client = HermesApiServerClient(
            AgentBackendConfig(
                id = "debug-hermes-live-probe",
                displayName = "Debug Hermes",
                type = BackendType.HERMES_API_SERVER,
                baseUrl = baseUrl,
                modelName = "hermes-agent",
                useRunsApi = useRuns,
                useStreaming = true,
            ),
        )
        val connection = client.testConnection()
        check(connection.ok) { "connection failed: ${connection.message}" }

        val reply = StringBuilder()
        withTimeout(60_000L) {
            client.sendMessage(
                messages = listOf(AgentMessage.user("Setup check: reply with a short confirmation.")),
                options = AgentSendOptions(sessionId = "agent-voice-debug-live-probe", stream = true),
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
        check(reply.isNotBlank()) { "empty reply" }
        return reply.toString()
    }

    companion object {
        const val TAG = "AgentVoiceHermesProbe"
        private const val EXTRA_BASE_URL = "baseUrl"
        private const val EXTRA_RUNS = "runs"
    }
}
