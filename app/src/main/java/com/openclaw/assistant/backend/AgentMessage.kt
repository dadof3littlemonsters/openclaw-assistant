package com.openclaw.assistant.backend

import kotlinx.serialization.Serializable

enum class AgentRole { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
data class AgentMessage(
    val role: String,
    val content: String,
) {
    companion object {
        fun system(text: String) = AgentMessage("system", text)
        fun user(text: String) = AgentMessage("user", text)
        fun assistant(text: String) = AgentMessage("assistant", text)
    }
}

data class AgentSendOptions(
    val sessionId: String? = null,
    val stream: Boolean = true,
    val modelOverride: String? = null,
    val stopOnDispose: Boolean = true,
    val extra: Map<String, String> = emptyMap(),
)

sealed class AgentEvent {
    data class Started(val runId: String? = null) : AgentEvent()
    data class TokenDelta(val text: String) : AgentEvent()
    data class MessageDelta(val role: String, val text: String) : AgentEvent()
    data class ToolProgress(val tool: String, val stage: String, val detail: String? = null) : AgentEvent()
    data class Completed(val finalText: String, val runId: String? = null) : AgentEvent()
    data class Error(val message: String, val cause: Throwable? = null) : AgentEvent()
}
