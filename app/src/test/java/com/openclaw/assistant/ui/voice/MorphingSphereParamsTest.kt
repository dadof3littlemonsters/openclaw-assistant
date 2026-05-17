package com.openclaw.assistant.ui.voice

import com.openclaw.assistant.service.AssistantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphingSphereParamsTest {

    @Test fun `idle is calmer than listening`() {
        val idleSlow = animationPeriodMs(AssistantState.IDLE).first
        val listenSlow = animationPeriodMs(AssistantState.LISTENING).first
        assertTrue("idle slower than listening", idleSlow > listenSlow)
    }

    @Test fun `thinking has the longest slow phase`() {
        val periods = AssistantState.values().associateWith { animationPeriodMs(it).first }
        val slowest = periods.maxByOrNull { it.value }!!.key
        assertTrue(slowest == AssistantState.THINKING || slowest == AssistantState.PROCESSING)
    }

    @Test fun `error animates fastest`() {
        val errorFast = animationPeriodMs(AssistantState.ERROR).second
        AssistantState.values().filter { it != AssistantState.ERROR }.forEach { s ->
            assertTrue("error faster than $s", errorFast <= animationPeriodMs(s).second)
        }
    }

    @Test fun `audio weight is zero in idle and error`() {
        assertEquals(0f, audioWeight(AssistantState.IDLE))
        assertEquals(0f, audioWeight(AssistantState.ERROR))
    }

    @Test fun `audio weight peaks during listening`() {
        val listening = audioWeight(AssistantState.LISTENING)
        assertTrue(listening >= audioWeight(AssistantState.SPEAKING))
        assertTrue(listening >= audioWeight(AssistantState.THINKING))
    }

    @Test fun `harmonicsFast amplitude grows with audio`() {
        val (_, quiet) = harmonicsFast(AssistantState.LISTENING, 0f)
        val (_, loud) = harmonicsFast(AssistantState.LISTENING, 1f)
        assertTrue("loud amplitude > quiet amplitude", loud > quiet)
    }

    @Test fun `stateColor differs per state`() {
        val colors = AssistantState.values().map { stateColor(it) }.toSet()
        // At minimum, distinct colours for listening / speaking / thinking / error.
        val key = listOf(AssistantState.LISTENING, AssistantState.SPEAKING, AssistantState.THINKING, AssistantState.ERROR)
            .map { stateColor(it) }.toSet()
        assertEquals(4, key.size)
    }
}
