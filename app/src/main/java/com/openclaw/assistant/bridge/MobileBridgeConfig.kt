package com.openclaw.assistant.bridge

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BridgeBindMode { LOCAL_ONLY, LAN }
enum class BridgeApprovalMode { ALWAYS_CONFIRM, CONFIRM_MEDIUM_HIGH, TRUSTED }

/**
 * Encrypted persistence for the Mobile Bridge. The bridge token is the only
 * gating credential between Hermes (or any external caller) and on-device
 * capabilities, so it is generated with [SecureRandom] and stored in
 * [EncryptedSharedPreferences]. Never log it.
 */
class MobileBridgeConfig internal constructor(private val prefs: SharedPreferences) {

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _port = MutableStateFlow(prefs.getInt(KEY_PORT, DEFAULT_PORT))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _bindMode = MutableStateFlow(loadBindMode())
    val bindMode: StateFlow<BridgeBindMode> = _bindMode.asStateFlow()

    private val _approvalMode = MutableStateFlow(loadApprovalMode())
    val approvalMode: StateFlow<BridgeApprovalMode> = _approvalMode.asStateFlow()

    /** Names of capability groups the user has opted in to. */
    private val _allowedCapabilityGroups = MutableStateFlow(loadAllowedGroups())
    val allowedCapabilityGroups: StateFlow<Set<String>> = _allowedCapabilityGroups.asStateFlow()

    fun setEnabled(value: Boolean) { prefs.edit { putBoolean(KEY_ENABLED, value) }; _enabled.value = value }
    fun setPort(value: Int) { prefs.edit { putInt(KEY_PORT, value) }; _port.value = value }
    fun setBindMode(mode: BridgeBindMode) { prefs.edit { putString(KEY_BIND_MODE, mode.name) }; _bindMode.value = mode }
    fun setApprovalMode(mode: BridgeApprovalMode) { prefs.edit { putString(KEY_APPROVAL_MODE, mode.name) }; _approvalMode.value = mode }
    fun setAllowedCapabilityGroups(groups: Set<String>) {
        prefs.edit { putStringSet(KEY_ALLOWED_GROUPS, groups) }
        _allowedCapabilityGroups.value = groups
    }

    /** Returns the existing token, generating one if absent. */
    fun getOrCreateToken(): String {
        prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return rotateToken()
    }

    fun rotateToken(): String {
        val token = generateToken()
        prefs.edit { putString(KEY_TOKEN, token) }
        return token
    }

    fun tokenOrNull(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun loadBindMode(): BridgeBindMode =
        runCatching { BridgeBindMode.valueOf(prefs.getString(KEY_BIND_MODE, null) ?: BridgeBindMode.LOCAL_ONLY.name) }
            .getOrDefault(BridgeBindMode.LOCAL_ONLY)
    private fun loadApprovalMode(): BridgeApprovalMode =
        runCatching { BridgeApprovalMode.valueOf(prefs.getString(KEY_APPROVAL_MODE, null) ?: BridgeApprovalMode.CONFIRM_MEDIUM_HIGH.name) }
            .getOrDefault(BridgeApprovalMode.CONFIRM_MEDIUM_HIGH)
    private fun loadAllowedGroups(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_GROUPS, DEFAULT_ALLOWED_GROUPS)?.toSet() ?: DEFAULT_ALLOWED_GROUPS

    companion object {
        const val DEFAULT_PORT = 8787
        private const val PREFS_NAME = "openclaw.bridge.secure"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_BIND_MODE = "bindMode"
        private const val KEY_APPROVAL_MODE = "approvalMode"
        private const val KEY_TOKEN = "token"
        private const val KEY_ALLOWED_GROUPS = "allowedCapabilityGroups"
        private val DEFAULT_ALLOWED_GROUPS: Set<String> = setOf("device", "apps", "clipboard.read")

        @Volatile private var instance: MobileBridgeConfig? = null
        fun getInstance(context: Context): MobileBridgeConfig = instance ?: synchronized(this) {
            instance ?: MobileBridgeConfig(createPrefs(context)).also { instance = it }
        }

        private fun createPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
