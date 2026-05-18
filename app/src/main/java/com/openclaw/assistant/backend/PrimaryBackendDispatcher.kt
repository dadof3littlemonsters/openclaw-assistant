package com.openclaw.assistant.backend

import android.content.Context
import com.openclaw.assistant.OpenClawApplication
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Single entry point used by voice (wake word, Voice Overlay, Assistant
 * activation, continuous conversation) and Chat to send a single user
 * message to whichever backend is currently Primary. Returns the final
 * assistant text (streaming is collapsed for the legacy text-only voice
 * pipeline; the new Chat UI gets per-token events through [AgentClient]
 * directly).
 */
object PrimaryBackendDispatcher {
    data class Reply(val text: String, val sourceDisplayName: String)

    /**
     * Sends to the selected enabled backend. If [backendId] is null, the
     * persisted Primary is used. Returns null only when there is no configured
     * target, allowing legacy installs without migrated backends to fall back to
     * the original settings-driven pipeline.
     */
    suspend fun send(
        context: Context,
        userText: String,
        backendId: String? = null,
        sessionId: String? = null,
        agentId: String? = null,
    ): Reply? {
        val manager = BackendManager.getInstance(context)
        val backends = manager.backends.first()
        val target = (backendId?.let { id -> backends.firstOrNull { it.id == id && it.enabled } }
            ?: backends.firstOrNull { it.enabled && it.isPrimary })
            ?: return null
        return when (target.type) {
            BackendType.HERMES_API_SERVER,
            BackendType.OPENCLAW_HTTP -> sendViaAgentClient(target, userText, sessionId, agentId)
            BackendType.OPENCLAW_GATEWAY -> sendViaGateway(context, target, userText)
        }
    }

    suspend fun sendPrimary(
        context: Context,
        userText: String,
        sessionId: String? = null,
        agentId: String? = null,
    ): Reply? = send(context, userText, backendId = null, sessionId = sessionId, agentId = agentId)

    /**
     * Backwards-compatible alias retained for older call sites during the
     * migration. It now sends to any Primary backend, not only Hermes.
     */
    suspend fun sendIfHermesPrimary(
        context: Context,
        userText: String,
    ): Reply? = sendPrimary(context, userText)

    private suspend fun sendViaAgentClient(
        target: AgentBackendConfig,
        userText: String,
        sessionId: String?,
        agentId: String?,
    ): Reply {
        val client = AgentClientFactory.create(target)
        val collected = StringBuilder()
        client.sendMessage(
            messages = listOf(AgentMessage.user(userText)),
            options = AgentSendOptions(
                sessionId = sessionId,
                stream = target.useStreaming,
                extra = mapOf("agentId" to agentId.orEmpty()),
            ),
        ).collect { event ->
            when (event) {
                is AgentEvent.TokenDelta -> collected.append(event.text)
                is AgentEvent.MessageDelta -> collected.append(event.text)
                is AgentEvent.Completed -> {
                    if (collected.isEmpty()) collected.append(event.finalText)
                }
                is AgentEvent.ToolProgress -> com.openclaw.assistant.ui.backend.ToolProgressFeed.push(event)
                is AgentEvent.Error -> throw RuntimeException("Hermes error: ${event.message}", event.cause)
                else -> Unit
            }
        }
        if (collected.isBlank()) {
            throw IllegalStateException("${target.displayName} returned an empty response. Check the backend model/provider configuration.")
        }
        return Reply(text = collected.toString(), sourceDisplayName = target.displayName)
    }

    private suspend fun sendViaGateway(
        context: Context,
        target: AgentBackendConfig,
        userText: String,
    ): Reply {
        val runtime = (context.applicationContext as OpenClawApplication).nodeRuntime
        if (!runtime.chatHealthOk.value) {
            throw IllegalStateException("OpenClaw Gateway is not connected")
        }

        val assistantCountBefore = runtime.chatMessages.value.count { it.role == "assistant" }
        runtime.sendChat(message = userText, thinking = "low", attachments = emptyList())

        val responseText = try {
            withTimeout(60_000L) {
                runtime.chatMessages
                    .first { messages -> messages.count { it.role == "assistant" } > assistantCountBefore }
                    .lastOrNull { it.role == "assistant" }
                    ?.content?.firstOrNull { it.type == "text" }?.text
            }
        } catch (_: TimeoutCancellationException) {
            null
        }

        if (responseText.isNullOrBlank()) {
            throw IllegalStateException("No response from OpenClaw Gateway")
        }
        return Reply(text = responseText, sourceDisplayName = target.displayName)
    }
}
