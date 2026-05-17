package com.openclaw.assistant.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Built-in Mobile Bridge capabilities. Only low/medium-risk read-only ones are
 * implemented here today; high-risk write actions (sms.send, camera.capture_photo,
 * contacts.* writes etc.) are intentionally left out — the manifest reflects what
 * is actually implementable, and `/execute` returns `unsupported_capability` for
 * everything else.
 */
object DeviceInfoCapability : BridgeCapability {
    override val name = "device.info"
    override val description = "Basic device identifiers and OS version"
    override val group = "device"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("appPackage", context.packageName)
    }
}

object AppsListCapability : BridgeCapability {
    override val name = "apps.list"
    override val description = "List launchable installed apps"
    override val group = "apps"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val apps = buildJsonArray {
            resolveInfos.take(500).forEach { ri ->
                add(buildJsonObject {
                    put("packageName", ri.activityInfo.packageName)
                    put("label", runCatching { ri.loadLabel(pm).toString() }.getOrNull() ?: ri.activityInfo.packageName)
                })
            }
        }
        return buildJsonObject { put("apps", apps); put("count", apps.size) }
    }
}

object ClipboardReadCapability : BridgeCapability {
    override val name = "clipboard.read"
    override val description = "Read current clipboard text"
    override val group = "clipboard.read"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        return buildJsonObject { put("text", text) }
    }
}

object DefaultCapabilities {
    val all: List<BridgeCapability> = listOf(
        DeviceInfoCapability,
        AppsListCapability,
        ClipboardReadCapability,
    )
}
