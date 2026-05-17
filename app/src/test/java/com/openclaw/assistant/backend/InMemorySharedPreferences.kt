package com.openclaw.assistant.backend

import android.content.SharedPreferences

/** Tiny in-memory SharedPreferences for unit tests — only the methods we use. */
internal class InMemorySharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    override fun getAll(): Map<String, *> = data.toMap()
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = InMemoryEditor(data)

    private class InMemoryEditor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(k: String, v: String?) = apply { staged[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { staged[k] = v }
        override fun putInt(k: String, v: Int) = apply { staged[k] = v }
        override fun putLong(k: String, v: Long) = apply { staged[k] = v }
        override fun putFloat(k: String, v: Float) = apply { staged[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { staged[k] = v }
        override fun remove(k: String) = apply { removed.add(k) }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { applyChanges(); return true }
        override fun apply() { applyChanges() }
        private fun applyChanges() {
            if (clear) target.clear()
            removed.forEach { target.remove(it) }
            target.putAll(staged)
        }
    }
}
