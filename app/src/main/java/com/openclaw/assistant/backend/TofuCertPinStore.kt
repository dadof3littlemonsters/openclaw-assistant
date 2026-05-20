package com.openclaw.assistant.backend

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.CertificatePinner
import java.security.MessageDigest

/**
 * Trust-On-First-Use cert pin store for Hermes HTTPS endpoints. The first
 * successful HTTPS connection to a hostname captures the leaf certificate's
 * SHA-256 fingerprint and persists it (encrypted). Subsequent connections to
 * that host MUST match.
 *
 * This is a defence against MITM on a paired backend's public URL — the
 * exact threat handled with TOFU cert pinning.
 *
 * Pin format follows [CertificatePinner.pin] — `sha256/<base64-encoded-hash>`.
 */
class TofuCertPinStore internal constructor(private val prefs: SharedPreferences) {

    /** SHA-256 base64 of the cert, e.g. "sha256/abc…=". */
    fun pin(host: String): String? = prefs.getString(host.lowercase(), null)

    /** Capture the first-seen pin for a host. No-op if already pinned. */
    fun captureIfAbsent(host: String, sha256Base64: String) {
        val key = host.lowercase()
        if (prefs.getString(key, null) == null) {
            prefs.edit { putString(key, sha256Base64) }
        }
    }

    fun clear(host: String) { prefs.edit { remove(host.lowercase()) } }

    fun knownHosts(): Set<String> = prefs.all.keys

    /**
     * Builds an OkHttp [CertificatePinner] from every known pin. Hosts not in
     * the store are NOT pinned (TOFU — first connection is the trust event).
     * After that connection completes successfully, callers should invoke
     * [captureIfAbsent] with the live leaf cert's SHA-256.
     */
    fun pinner(): CertificatePinner {
        val builder = CertificatePinner.Builder()
        prefs.all.forEach { (host, value) ->
            (value as? String)?.let { pin -> builder.add(host, pin) }
        }
        return builder.build()
    }

    companion object {
        private const val PREFS_NAME = "agentvoice.tofu.pins"

        @Volatile private var instance: TofuCertPinStore? = null
        fun getInstance(context: Context): TofuCertPinStore = instance ?: synchronized(this) {
            instance ?: TofuCertPinStore(createPrefs(context)).also { instance = it }
        }

        private fun createPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        /** Convenience: format raw cert bytes as the `sha256/<base64>` pin string. */
        fun pinOf(certDer: ByteArray): String {
            val sha = MessageDigest.getInstance("SHA-256").digest(certDer)
            return "sha256/" + android.util.Base64.encodeToString(sha, android.util.Base64.NO_WRAP)
        }
    }
}
