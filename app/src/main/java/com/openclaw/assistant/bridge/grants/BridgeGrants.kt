package com.openclaw.assistant.bridge.grants

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-capability grant store with TTL — Hermes-Relay calls these
 * "per-channel grants, user-chosen TTLs, revocable from any client".
 *
 * A grant lets a capability run without re-prompting for [Grant.expiresAtMs]
 * milliseconds. Default TTL choices are 10 minutes / 1 hour / "until I revoke"
 * (Long.MAX_VALUE). Any client (UI or remote `/revoke` call) can drop them.
 */
object BridgeGrants {
    data class Grant(val capability: String, val expiresAtMs: Long)

    private val grants = ConcurrentHashMap<String, Grant>()

    fun grant(capability: String, ttlMs: Long) {
        val expires = if (ttlMs >= Long.MAX_VALUE / 2) Long.MAX_VALUE else System.currentTimeMillis() + ttlMs
        grants[capability] = Grant(capability, expires)
    }

    fun isGranted(capability: String): Boolean {
        val g = grants[capability] ?: return false
        if (g.expiresAtMs <= System.currentTimeMillis()) { grants.remove(capability); return false }
        return true
    }

    fun snapshot(): List<Grant> {
        val now = System.currentTimeMillis()
        grants.values.removeAll { it.expiresAtMs <= now }
        return grants.values.toList().sortedBy { it.capability }
    }

    fun revoke(capability: String): Boolean = grants.remove(capability) != null
    fun revokeAll() { grants.clear() }
}

/**
 * Destructive-verb list. Hermes-Relay enforces an extra "are you sure?"
 * prompt when a capability's name (or arguments) match a destructive verb.
 *
 * Matching is case-insensitive substring on the capability name; tools that
 * want to bypass the check would have to pick a non-destructive name, which
 * is the point.
 */
object DestructiveVerbs {
    val verbs: Set<String> = setOf(
        "delete", "remove", "wipe", "drop", "erase", "destroy", "uninstall",
        "send", "post", "publish", "transfer", "pay", "purchase",
        "format", "shutdown", "reboot",
    )
    fun isDestructive(capability: String): Boolean {
        val lower = capability.lowercase()
        return verbs.any { lower.contains(it) }
    }
}
