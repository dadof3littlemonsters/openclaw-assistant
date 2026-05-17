package com.openclaw.assistant.backend

/**
 * Normalizes a user-entered base URL into a `<scheme>://<host>[:<port>]/v1` root.
 *
 * Accepts:
 *  - http://host:8642
 *  - http://host:8642/
 *  - http://host:8642/v1
 *  - http://host:8642/v1/
 *  - https://host/openai/v1
 *
 * Always strips a trailing slash and ensures the path ends in `/v1`.
 */
internal object HermesUrl {
    fun normalizeBase(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Base URL is empty" }
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "Base URL must start with http:// or https://"
        }
        return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
    }

    fun chatCompletionsUrl(base: String) = "${normalizeBase(base)}/chat/completions"
    fun modelsUrl(base: String) = "${normalizeBase(base)}/models"
    fun healthUrl(base: String): String {
        val n = normalizeBase(base)
        return "$n/health"
    }
    fun rootHealthUrl(base: String): String {
        val n = normalizeBase(base)
        return n.removeSuffix("/v1") + "/health"
    }
    fun runsUrl(base: String) = "${normalizeBase(base)}/runs"
    fun runUrl(base: String, id: String) = "${normalizeBase(base)}/runs/$id"
    fun runEventsUrl(base: String, id: String) = "${normalizeBase(base)}/runs/$id/events"
    fun runStopUrl(base: String, id: String) = "${normalizeBase(base)}/runs/$id/stop"
}
