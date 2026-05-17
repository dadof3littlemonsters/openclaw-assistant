package com.openclaw.assistant.backend

import android.content.Context
import kotlinx.coroutines.flow.first

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
     * Returns:
     *   - `null` if the user has not configured any Hermes Primary backend
     *     (the caller should then fall back to the legacy OpenClaw HTTP
     *     pipeline so existing setups behave exactly as before).
     *   - A [Reply] with the assistant text if a Hermes Primary handled it.
     *
     * The function deliberately swallows nothing: callers must propagate
     * thrown exceptions so they show up in the UI / wake-word error state.
     */
    suspend fun sendIfHermesPrimary(
        context: Context,
        userText: String,
    ): Reply? {
        val manager = BackendManager.getInstance(context)
        val primary = manager.backends.first().firstOrNull { it.enabled && it.isPrimary }
            ?: return null
        if (primary.type != BackendType.HERMES_API_SERVER) return null
        val client = AgentClientFactory.create(primary)
        val collected = StringBuilder()
        client.sendMessage(
            messages = listOf(AgentMessage.user(userText)),
            options = AgentSendOptions(stream = primary.useStreaming),
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
        return Reply(text = collected.toString(), sourceDisplayName = primary.displayName)
    }
}
