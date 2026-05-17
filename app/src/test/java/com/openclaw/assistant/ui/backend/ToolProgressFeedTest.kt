package com.openclaw.assistant.ui.backend

import com.openclaw.assistant.backend.AgentEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolProgressFeedTest {

    @After fun tearDown() { ToolProgressFeed.clear() }

    @Test fun `feed starts empty`() {
        assertEquals(emptyList<AgentEvent.ToolProgress>(), ToolProgressFeed.events.value)
    }

    @Test fun `push orders newest first`() {
        ToolProgressFeed.push(AgentEvent.ToolProgress("a", "start"))
        ToolProgressFeed.push(AgentEvent.ToolProgress("b", "start"))
        assertEquals(listOf("b", "a"), ToolProgressFeed.events.value.map { it.tool })
    }

    @Test fun `feed caps at five entries`() {
        repeat(8) { ToolProgressFeed.push(AgentEvent.ToolProgress("t$it", "stage")) }
        val tools = ToolProgressFeed.events.value.map { it.tool }
        assertEquals(5, tools.size)
        assertEquals(listOf("t7", "t6", "t5", "t4", "t3"), tools)
    }
}
