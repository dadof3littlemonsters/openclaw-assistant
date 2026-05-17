package com.openclaw.assistant.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Capabilities backed by [AgentVoiceAccessibilityService]. All MEDIUM/HIGH
 * risk, all require the user to (a) be on a sideload build and (b) have
 * explicitly enabled the Accessibility Bridge in system settings.
 *
 * `isAvailable` returns false on Play builds so the manifest never advertises
 * an action the runtime cannot deliver. On sideload, it returns false until
 * the user actually flips the system toggle.
 */
private fun a11yAvailable(): Boolean = BuildConfig.IS_SIDELOAD && AgentVoiceAccessibilityService.isRunning()

object ScreenTapCapability : BridgeCapability {
    override val name = "screen.tap"
    override val description = "Tap a point on the screen"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.MEDIUM
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val x = arguments["x"]?.jsonPrimitive?.content?.toFloatOrNull()
        val y = arguments["y"]?.jsonPrimitive?.content?.toFloatOrNull()
        if (x == null || y == null) return buildJsonObject { put("ok", false); put("reason", "x,y required") }
        val ok = AgentVoiceAccessibilityService.get()?.performTap(x, y) ?: false
        return buildJsonObject { put("ok", ok) }
    }
}

object ScreenSwipeCapability : BridgeCapability {
    override val name = "screen.swipe"
    override val description = "Swipe from (x1,y1) to (x2,y2) over durationMs"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.MEDIUM
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val x1 = arguments["x1"]?.jsonPrimitive?.content?.toFloatOrNull()
        val y1 = arguments["y1"]?.jsonPrimitive?.content?.toFloatOrNull()
        val x2 = arguments["x2"]?.jsonPrimitive?.content?.toFloatOrNull()
        val y2 = arguments["y2"]?.jsonPrimitive?.content?.toFloatOrNull()
        val ms = arguments["durationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 250L
        if (x1 == null || y1 == null || x2 == null || y2 == null)
            return buildJsonObject { put("ok", false); put("reason", "x1/y1/x2/y2 required") }
        val ok = AgentVoiceAccessibilityService.get()?.performSwipe(x1, y1, x2, y2, ms) ?: false
        return buildJsonObject { put("ok", ok) }
    }
}

object ScreenHomeCapability : BridgeCapability {
    override val name = "screen.home"
    override val description = "Press the Home key via Accessibility Bridge"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.LOW
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val ok = AgentVoiceAccessibilityService.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
        return buildJsonObject { put("ok", ok) }
    }
}

object ScreenBackCapability : BridgeCapability {
    override val name = "screen.back"
    override val description = "Press the Back key via Accessibility Bridge"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.LOW
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val ok = AgentVoiceAccessibilityService.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
        return buildJsonObject { put("ok", ok) }
    }
}

object ScreenWindowDescribeCapability : BridgeCapability {
    override val name = "screen.window.describe"
    override val description = "Describe the active window's title and root content description"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.LOW
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val svc = AgentVoiceAccessibilityService.get()
            ?: return buildJsonObject { put("ok", false); put("reason", "accessibility off") }
        val root = svc.rootInActiveWindow
        return buildJsonObject {
            put("ok", root != null)
            put("packageName", root?.packageName?.toString() ?: "")
            put("contentDescription", root?.contentDescription?.toString() ?: "")
            put("text", root?.text?.toString() ?: "")
        }
    }
}

object A11yCapabilities {
    val all: List<BridgeCapability> = listOf(
        ScreenTapCapability, ScreenSwipeCapability,
        ScreenHomeCapability, ScreenBackCapability,
        ScreenWindowDescribeCapability,
    )
}
