package com.openclaw.assistant.bridge

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Serializable
data class BridgeActivityEntry(
    val id: String,
    val capability: String,
    val riskLevel: String,
    val status: String,
    val message: String?,
    val timestampMs: Long,
)

object BridgeActivityLog {
    private const val PREFS_NAME = "openclaw.bridge.activity"
    private const val KEY_ENTRIES = "entries.v1"
    private const val MAX_ENTRIES = 100

    private val _entries = MutableStateFlow<List<BridgeActivityEntry>>(emptyList())
    val entries: StateFlow<List<BridgeActivityEntry>> = _entries.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _entries.value = decode(prefs?.getString(KEY_ENTRIES, null).orEmpty())
        }
    }

    fun record(context: Context, capability: String, riskLevel: RiskLevel?, status: String, message: String? = null) {
        initialize(context)
        val entry = BridgeActivityEntry(
            id = "${System.currentTimeMillis()}-$capability",
            capability = capability,
            riskLevel = riskLevel?.name?.lowercase().orEmpty(),
            status = status,
            message = message?.take(180),
            timestampMs = System.currentTimeMillis(),
        )
        val next = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        _entries.value = next
        prefs?.edit { putString(KEY_ENTRIES, encode(next)) }
    }

    fun clear(context: Context) {
        initialize(context)
        _entries.value = emptyList()
        prefs?.edit { remove(KEY_ENTRIES) }
    }

    private fun encode(entries: List<BridgeActivityEntry>): String =
        json.encodeToString(ListSerializer(BridgeActivityEntry.serializer()), entries)

    private fun decode(raw: String): List<BridgeActivityEntry> = runCatching {
        json.decodeFromString(ListSerializer(BridgeActivityEntry.serializer()), raw)
    }.getOrDefault(emptyList())
}
