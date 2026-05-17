package com.openclaw.assistant.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure settings storage for the Wear OS app.
 * Self-contained: does NOT depend on the phone :app module.
 */
class WearSettingsRepository private constructor(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var httpUrl: String
        get() = prefs.getString(KEY_HTTP_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HTTP_URL, value).apply()

    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    var speechLanguage: String
        get() = prefs.getString(KEY_SPEECH_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPEECH_LANGUAGE, value).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.1f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()

    fun isConfigured(): Boolean = httpUrl.isNotBlank()

    /**
     * Returns the chat-completions URL the watch should POST to.
     *
     * To target the phone's Primary backend from the watch, point this URL
     * at the same Hermes (or OpenClaw) endpoint the phone uses:
     *  - Hermes: `http://host:8642` (Bearer = your Hermes API key)
     *  - OpenClaw HTTP: `http://host:8080`
     *
     * Both backends speak OpenAI-compatible chat completions, so the same
     * Wear payload reaches whichever one is currently Primary on the phone.
     */
    fun getChatUrl(): String {
        val base = httpUrl.trim().trimEnd('/').removeSuffix("/v1")
        return if (base.isBlank()) "" else "$base/v1/chat/completions"
    }

    companion object {
        private const val PREFS_NAME = "wear_openclaw_prefs"
        private const val KEY_HTTP_URL = "http_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_SPEECH_LANGUAGE = "speech_language"
        private const val KEY_TTS_SPEED = "tts_speed"

        @Volatile
        private var instance: WearSettingsRepository? = null

        fun getInstance(context: Context): WearSettingsRepository =
            instance ?: synchronized(this) {
                instance ?: WearSettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
