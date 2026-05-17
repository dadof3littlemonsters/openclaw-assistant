package com.openclaw.assistant.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Races a list of candidate Hermes API Server URLs (LAN, Tailscale, public)
 * in parallel and returns the first one to respond successfully to
 * `GET /v1/models` (with `/health` fallback).
 *
 * Modelled after Hermes-Relay's "LAN + Tailscale + public URLs" pattern: every
 * connect picks the lowest-latency reachable endpoint, so the same paired
 * backend works at home, on a train, or behind a VPN without reconfiguration.
 *
 * The racer never falls back to a non-2xx endpoint — auth failure on the
 * fastest endpoint is still preferred over silently using a slow stale one.
 */
class HermesEndpointRacer(
    private val httpClient: OkHttpClient = defaultClient(),
    private val perEndpointTimeoutMs: Long = 4_000L,
) {
    data class Outcome(val url: String, val ok: Boolean, val latencyMs: Long, val httpStatus: Int? = null, val errorMessage: String? = null)

    /** Returns the winning endpoint, or null if none responded within the budget. */
    suspend fun race(candidates: List<String>, token: String?): Outcome? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null
        val winner = CompletableDeferred<Outcome>()
        val results = mutableListOf<Outcome>()
        val resultsLock = Any()
        val jobs = candidates.distinct().map { candidate ->
            launch(Dispatchers.IO) {
                val outcome = probe(candidate, token)
                synchronized(resultsLock) { results += outcome }
                if (outcome.ok && !winner.isCompleted) winner.complete(outcome)
            }
        }
        val first = withTimeoutOrNull(perEndpointTimeoutMs + 500L) { winner.await() }
        jobs.forEach { it.cancel() }
        first ?: synchronized(resultsLock) {
            results.minByOrNull { it.latencyMs }
        }
    }

    private suspend fun probe(candidate: String, token: String?): Outcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val req = Request.Builder().url(HermesUrl.modelsUrl(candidate)).apply {
                token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }.get().build()
            httpClient.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - started
                if (resp.isSuccessful) return@withContext Outcome(candidate, true, elapsed, resp.code)
                if (resp.code == 404) {
                    val healthReq = Request.Builder().url(HermesUrl.healthUrl(candidate)).apply {
                        token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                    }.get().build()
                    httpClient.newCall(healthReq).execute().use { h ->
                        return@withContext Outcome(candidate, h.isSuccessful, System.currentTimeMillis() - started, h.code)
                    }
                }
                Outcome(candidate, false, elapsed, resp.code, "HTTP ${resp.code}")
            }
        } catch (e: Throwable) {
            Outcome(candidate, false, System.currentTimeMillis() - started, null, e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Process-wide cache of "which endpoint is currently winning for backend X".
 * Refreshed on every successful race; consumed by [HermesApiServerClient]
 * so streaming requests do not have to re-probe.
 */
object HermesEndpointSelection {
    private val _selected = MutableStateFlow<Map<String, String>>(emptyMap())
    val selected: StateFlow<Map<String, String>> = _selected.asStateFlow()
    fun remember(backendId: String, winner: String) {
        _selected.value = _selected.value + (backendId to winner)
    }
    fun forBackend(backendId: String): String? = _selected.value[backendId]
}
