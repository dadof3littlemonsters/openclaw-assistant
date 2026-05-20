package com.openclaw.assistant.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

object ScreenLongPressCapability : BridgeCapability {
    override val name = "screen.long_press"
    override val description = "Long-press a point on the screen"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.MEDIUM
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val x = arguments["x"]?.jsonPrimitive?.content?.toFloatOrNull()
        val y = arguments["y"]?.jsonPrimitive?.content?.toFloatOrNull()
        val ms = arguments["durationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 600L
        if (x == null || y == null) return buildJsonObject { put("ok", false); put("reason", "x,y required") }
        val ok = AgentVoiceAccessibilityService.get()?.performLongPress(x, y, ms) ?: false
        return buildJsonObject { put("ok", ok) }
    }
}

object ScreenDragCapability : BridgeCapability {
    override val name = "screen.drag"
    override val description = "Drag from (x1,y1) to (x2,y2)"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.MEDIUM
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject =
        ScreenSwipeCapability.execute(context, arguments)
}

object ScreenFindNodesCapability : BridgeCapability {
    override val name = "screen.find_nodes"
    override val description = "Find visible accessibility nodes by text, class, or clickable state"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.LOW
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val nodes = AgentVoiceAccessibilityService.get()?.findNodes(
            text = arguments["text"]?.jsonPrimitive?.content,
            className = arguments["className"]?.jsonPrimitive?.content,
            clickable = arguments["clickable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            limit = arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20,
        ).orEmpty()
        return buildJsonObject {
            put("nodes", buildJsonArray {
                nodes.forEach { node ->
                    add(buildJsonObject {
                        put("nodeId", node.nodeId)
                        put("text", node.text ?: "")
                        put("contentDescription", node.contentDescription ?: "")
                        put("className", node.className ?: "")
                        put("viewId", node.viewId ?: "")
                        put("bounds", node.bounds.toJson())
                        put("clickable", node.clickable)
                        put("longClickable", node.longClickable)
                        put("scrollable", node.scrollable)
                        put("editable", node.editable)
                        put("enabled", node.enabled)
                    })
                }
            })
        }
    }
}

object ScreenDescribeNodeCapability : BridgeCapability {
    override val name = "screen.describe_node"
    override val description = "Describe one visible accessibility node by nodeId"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.LOW
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val nodeId = arguments["nodeId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val node = AgentVoiceAccessibilityService.get()?.describeNode(nodeId)
        return if (node == null) {
            buildJsonObject { put("found", false); put("nodeId", nodeId) }
        } else {
            buildJsonObject {
                put("found", true)
                put("nodeId", node.nodeId)
                put("text", node.text ?: "")
                put("contentDescription", node.contentDescription ?: "")
                put("className", node.className ?: "")
                put("viewId", node.viewId ?: "")
                put("bounds", node.bounds.toJson())
                put("clickable", node.clickable)
                put("longClickable", node.longClickable)
                put("scrollable", node.scrollable)
                put("editable", node.editable)
                put("enabled", node.enabled)
            }
        }
    }
}

object ScreenHashCapability : BridgeCapability {
    override val name = "screen.hash"
    override val description = "Return a stable hash of visible accessibility content"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.LOW
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val hash = AgentVoiceAccessibilityService.get()?.screenHash()
            ?: return buildJsonObject { put("ok", false); put("reason", "accessibility off") }
        return buildJsonObject {
            put("hash", hash.hash)
            put("nodeCount", hash.nodeCount)
            put("truncated", hash.truncated)
        }
    }
}

object ScreenDiffCapability : BridgeCapability {
    override val name = "screen.diff"
    override val description = "Compare the current screen hash with a previous hash"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.LOW
    override fun isAvailable(context: Context) = a11yAvailable()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val previous = arguments["previousHash"]?.jsonPrimitive?.content.orEmpty()
        val hash = AgentVoiceAccessibilityService.get()?.screenHash()
            ?: return buildJsonObject { put("ok", false); put("reason", "accessibility off") }
        return buildJsonObject {
            put("changed", hash.hash != previous)
            put("hash", hash.hash)
            put("nodeCount", hash.nodeCount)
            put("truncated", hash.truncated)
        }
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
        val snapshot = svc.readScreenSnapshot()
        val first = snapshot.nodes.firstOrNull()
        return buildJsonObject {
            put("ok", true)
            put("packageName", snapshot.packageName ?: "")
            put("contentDescription", first?.contentDescription ?: "")
            put("text", first?.text ?: "")
            put("nodeCount", snapshot.nodes.size)
            put("truncated", snapshot.truncated)
        }
    }
}

object A11yCapabilities {
    val all: List<BridgeCapability> = listOf(
        ScreenTapCapability, ScreenSwipeCapability, ScreenLongPressCapability, ScreenDragCapability,
        ScreenHomeCapability, ScreenBackCapability,
        ScreenWindowDescribeCapability, ScreenFindNodesCapability, ScreenDescribeNodeCapability,
        ScreenHashCapability, ScreenDiffCapability,
    )
}

private fun com.openclaw.assistant.bridge.accessibility.BoundsSnapshot.toJson(): JsonObject =
    buildJsonObject {
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
        put("centerX", centerX)
        put("centerY", centerY)
    }
