package com.openclaw.assistant.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    private fun feedAll(lines: List<String>): List<SseEvent> {
        val parser = SseParser()
        val out = mutableListOf<SseEvent>()
        for (l in lines) parser.feed(l)?.let(out::add)
        return out
    }

    @Test fun `single event emits on blank line`() {
        val events = feedAll(listOf("event: foo", "data: hello", ""))
        assertEquals(1, events.size)
        assertEquals("foo", events[0].event)
        assertEquals("hello", events[0].data)
    }

    @Test fun `multiple data lines join with newline`() {
        val events = feedAll(listOf("data: line1", "data: line2", "data: line3", ""))
        assertEquals("line1\nline2\nline3", events[0].data)
    }

    @Test fun `done sentinel is delivered as data`() {
        val events = feedAll(listOf("data: [DONE]", ""))
        assertEquals("[DONE]", events[0].data)
    }

    @Test fun `comment line is ignored`() {
        val events = feedAll(listOf(": keepalive", "data: x", ""))
        assertEquals(1, events.size)
        assertEquals("x", events[0].data)
    }

    @Test fun `field without leading space is preserved`() {
        val events = feedAll(listOf("data:no-space", ""))
        assertEquals("no-space", events[0].data)
    }

    @Test fun `multiple events in sequence`() {
        val events = feedAll(listOf("data: a", "", "data: b", ""))
        assertEquals(listOf("a", "b"), events.map { it.data })
    }
}
