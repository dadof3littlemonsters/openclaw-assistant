package com.openclaw.assistant.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test fun `bucket starts full`() {
        val rl = RateLimiter(capacity = 5, refillIntervalMs = 1000L) { 0L }
        repeat(5) { assertTrue("call ${it + 1} should pass", rl.tryAcquire("k")) }
        assertFalse(rl.tryAcquire("k"))
    }

    @Test fun `refill happens deterministically with clock`() {
        var now = 0L
        val rl = RateLimiter(capacity = 2, refillIntervalMs = 100L) { now }
        assertTrue(rl.tryAcquire("k")); assertTrue(rl.tryAcquire("k"))
        assertFalse(rl.tryAcquire("k"))
        now = 250L // 2.5 tokens worth of refill, capped at capacity
        assertTrue(rl.tryAcquire("k")); assertTrue(rl.tryAcquire("k"))
        assertFalse(rl.tryAcquire("k"))
    }

    @Test fun `buckets are per key`() {
        val rl = RateLimiter(capacity = 1, refillIntervalMs = 1000L) { 0L }
        assertTrue(rl.tryAcquire("a"))
        assertFalse(rl.tryAcquire("a"))
        assertTrue(rl.tryAcquire("b"))
    }
}
