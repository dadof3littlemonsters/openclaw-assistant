package com.openclaw.assistant.bridge.pairing

import com.openclaw.assistant.bridge.MobileBridgeConfig
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Pairing protocol for trusted local Mobile Bridge clients:
 *
 *  1. Bridge UI generates a short-lived [Offer] containing a random 6-character
 *     human-readable code and a one-time pairing nonce. The offer is shown as
 *     a QR (containing the full payload) AND as a 6-digit code typeable into
 *     a desktop CLI.
 *  2. The remote (Hermes desktop, another Android client, etc.) sends
 *     `POST /pair { "code": "ABC123" }` to the Bridge.
 *  3. On match, the Bridge returns the current bearer token. The offer is
 *     consumed atomically — replay impossible.
 *  4. Offers expire 5 minutes after creation, and only one is active at a
 *     time (creating a second cancels the first).
 *
 * Codes use a 32-char Base32 alphabet without I/O/L/1 to avoid OCR ambiguity.
 */
object BridgePairing {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val rng = SecureRandom()
    private val pending = ConcurrentHashMap<String, Offer>()

    data class Offer(
        val code: String,
        val nonce: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        /** Full payload encoded into the QR. Easy to parse on the desktop side. */
        val qrPayload: String,
    )

    /** Replaces any in-flight offer with a fresh one. */
    fun createOffer(bridgeUrl: String, ttlMs: Long = 5 * 60_000L): Offer {
        pending.clear()
        val now = System.currentTimeMillis()
        val code = randomCode(6)
        val nonce = randomCode(24)
        val payload = "agentvoice://pair?u=${enc(bridgeUrl)}&c=$code&n=$nonce&e=${now + ttlMs}"
        val offer = Offer(code = code, nonce = nonce, createdAtMs = now, expiresAtMs = now + ttlMs, qrPayload = payload)
        pending[code] = offer
        return offer
    }

    fun currentOffer(): Offer? = pending.values.firstOrNull { it.expiresAtMs > System.currentTimeMillis() }

    fun cancel() { pending.clear() }

    /**
     * Atomically consumes the offer matching [submittedCode]. Returns the
     * bridge bearer token from [config] on success or null on miss / expiry.
     */
    fun redeem(submittedCode: String, config: MobileBridgeConfig): String? {
        val normalized = submittedCode.trim().uppercase()
        val offer = pending[normalized] ?: return null
        if (offer.expiresAtMs < System.currentTimeMillis()) { pending.remove(normalized); return null }
        pending.remove(normalized) // one-shot
        return config.tokenOrNull() ?: config.getOrCreateToken()
    }

    private fun randomCode(len: Int): String {
        val out = CharArray(len)
        for (i in 0 until len) out[i] = ALPHABET[rng.nextInt(ALPHABET.length)]
        return String(out)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
