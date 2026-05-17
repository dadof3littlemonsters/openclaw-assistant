package com.openclaw.assistant.bridge

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class RiskLevel { LOW, MEDIUM, HIGH }

interface BridgeCapability {
    val name: String
    val description: String
    val group: String
    val riskLevel: RiskLevel
    val requiresPermissions: List<String> get() = emptyList()

    /** Static input schema descriptor (JSON-schema-ish object). */
    val inputSchema: JsonObject get() = buildJsonObject {}

    /**
     * Whether this capability is implementable on the current device. Capabilities
     * that report `false` are NOT advertised in /manifest and /execute will
     * return `unsupported_capability`.
     */
    fun isAvailable(context: Context): Boolean = true

    /** Run the capability. Must not block the calling thread for an unbounded amount of time. */
    suspend fun execute(context: Context, arguments: JsonObject): JsonObject

    fun manifestEntry(context: Context): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("group", group)
        put("riskLevel", riskLevel.name.lowercase())
        put("requiresPermission", buildJsonArray { requiresPermissions.forEach { add(JsonPrimitive(it)) } })
        put("inputSchema", inputSchema)
    }
}
