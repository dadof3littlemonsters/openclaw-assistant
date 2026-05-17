package com.openclaw.assistant.backend

/**
 * Minimal SSE event used by [SseParser]. `data` joins multiple `data:` lines with `\n`.
 */
data class SseEvent(
    val event: String?,
    val data: String,
    val id: String? = null,
)

/**
 * Streaming SSE line parser. Feed one line at a time (without the trailing newline);
 * the parser emits a fully-formed [SseEvent] whenever it sees a blank separator line.
 *
 * Per the SSE spec, lines starting with `:` are comments and ignored.
 */
class SseParser {
    private var event: String? = null
    private var id: String? = null
    private val data = StringBuilder()

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (data.isEmpty() && event == null && id == null) return null
            val ev = SseEvent(event, data.toString(), id)
            reset()
            return ev
        }
        if (line.startsWith(":")) return null

        val (field, value) = splitField(line)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            "id" -> id = value
        }
        return null
    }

    private fun splitField(line: String): Pair<String, String> {
        val idx = line.indexOf(':')
        if (idx < 0) return line to ""
        val field = line.substring(0, idx)
        var value = line.substring(idx + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        return field to value
    }

    private fun reset() {
        event = null
        id = null
        data.clear()
    }
}
