package com.openclaw.assistant.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-client token-bucket rate limiter. Independent of the OS clock, accepts
 * a `nowMs` source so tests can drive it deterministically.
 *
 * Default capacity of 30 requests with a refill of 1 token / 200 ms (i.e. up
 * to ~5 req/s sustained, 30 burst). The bridge is local so we don't need to
 * be aggressive, but auth brute-force attempts and runaway tool loops both
 * benefit from a cap.
 */
class RateLimiter(
    private val capacity: Int = 30,
    private val refillIntervalMs: Long = 200L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Bucket(var tokens: Double, var lastRefillMs: Long)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(key: String): Boolean {
        val nowMs = now()
        val bucket = buckets.compute(key) { _, existing ->
            val b = existing ?: Bucket(capacity.toDouble(), nowMs)
            val elapsed = nowMs - b.lastRefillMs
            if (elapsed > 0) {
                b.tokens = (b.tokens + elapsed.toDouble() / refillIntervalMs).coerceAtMost(capacity.toDouble())
                b.lastRefillMs = nowMs
            }
            b
        }!!
        return if (bucket.tokens >= 1.0) { bucket.tokens -= 1.0; true } else false
    }
}
