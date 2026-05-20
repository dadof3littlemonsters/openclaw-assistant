package com.openclaw.assistant.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Bridge — Hermes-Relay's "the agent reads your screen and acts
 * on it: tap, type, swipe, screenshots, clipboard, media, notifications".
 *
 * The user must explicitly enable WakeHermesClaw in Android Settings →
 * Accessibility before any of the screen.* capabilities can run. The service
 * is intentionally a thin "remote control" — it has no auto-behaviour, no
 * background scraping, no event-stream broadcasting. It only acts when a
 * capability invocation reaches it.
 *
 * This service is exposed only on sideload builds (`BuildConfig.IS_SIDELOAD`)
 * because Google Play restricts accessibility services that are not assistive
 * to disabled users; the Play track keeps the manifest entry but the
 * AccessibilityCapabilities advertise `isAvailable = false` to keep the bridge
 * honest.
 */
class AgentVoiceAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op: pull-driven service */ }

    fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    companion object {
        @Volatile private var instance: AgentVoiceAccessibilityService? = null
        fun get(): AgentVoiceAccessibilityService? = instance
        fun isRunning(): Boolean = instance != null
    }
}
