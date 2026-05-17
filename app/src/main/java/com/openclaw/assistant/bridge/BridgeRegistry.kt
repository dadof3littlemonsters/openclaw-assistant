package com.openclaw.assistant.bridge

import android.content.Context

/**
 * Holds the runtime list of [BridgeCapability]s. The set is filtered by
 * [MobileBridgeConfig.allowedCapabilityGroups] so the manifest only ever
 * advertises what the user opted in to AND what the device can actually run.
 */
class BridgeRegistry(private val capabilities: List<BridgeCapability> = FullCapabilities.all) {

    fun visible(context: Context, allowedGroups: Set<String>): List<BridgeCapability> =
        capabilities.filter { it.isAvailable(context) && it.group in allowedGroups }

    fun byName(name: String): BridgeCapability? = capabilities.firstOrNull { it.name == name }
}
