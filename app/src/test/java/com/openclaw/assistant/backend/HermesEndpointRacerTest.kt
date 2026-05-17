package com.openclaw.assistant.backend

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesEndpointRacerTest {
    private lateinit var fast: MockWebServer
    private lateinit var slow: MockWebServer
    private lateinit var bad: MockWebServer

    @Before fun setUp() {
        fast = MockWebServer().apply { start() }
        slow = MockWebServer().apply { start() }
        bad = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        fast.shutdown(); slow.shutdown(); bad.shutdown()
    }

    @Test fun `winner is fastest 200 responder`() = runBlocking {
        fast.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        slow.enqueue(MockResponse().setBody("{}").setResponseCode(200).setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS))
        val outcome = HermesEndpointRacer().race(listOf(slow.url("/").toString().trimEnd('/'), fast.url("/").toString().trimEnd('/')), token = null)
        assertNotNull(outcome)
        assertTrue(outcome!!.ok)
        assertEquals(fast.url("/").toString().trimEnd('/'), outcome.url)
    }

    @Test fun `4xx loser fails the race`() = runBlocking {
        bad.enqueue(MockResponse().setResponseCode(500))
        val outcome = HermesEndpointRacer().race(listOf(bad.url("/").toString().trimEnd('/')), token = null)
        assertNotNull(outcome)
        assertTrue(!outcome!!.ok)
    }

    @Test fun `empty candidates yields null`() = runBlocking {
        assertNull(HermesEndpointRacer().race(emptyList(), token = null))
    }
}
