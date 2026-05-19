package com.openclaw.assistant.node

import android.content.Context
import com.openclaw.assistant.bridge.BridgeBindMode
import com.openclaw.assistant.bridge.BridgeRegistry
import com.openclaw.assistant.bridge.MobileBridgeConfig
import com.openclaw.assistant.bridge.MobileBridgeServer
import com.openclaw.assistant.gateway.GatewaySession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MobileBridgeHandler(
  private val context: Context,
  private val config: MobileBridgeConfig,
  private val registry: BridgeRegistry = BridgeRegistry(),
  private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
  private val server = MobileBridgeServer(
    context = context,
    config = config,
    registry = registry,
    json = json,
  )

  fun handleStatus(): GatewaySession.InvokeResult {
    val allowed = config.allowedCapabilityGroups.value
    val visibleCount = registry.visible(context, allowed).size
    val bindMode = config.bindMode.value
    val baseUrl =
      if (bindMode == BridgeBindMode.LOCAL_ONLY) {
        "http://127.0.0.1:${config.port.value}"
      } else {
        "http://0.0.0.0:${config.port.value}"
      }
    return GatewaySession.InvokeResult.ok(
      buildJsonObject {
        put("enabled", config.enabled.value)
        put("port", config.port.value)
        put("bindMode", bindMode.name.lowercase())
        put("approvalMode", config.approvalMode.value.name.lowercase())
        put("baseUrl", baseUrl)
        put("allowedCapabilityGroups", buildJsonArray { allowed.forEach { add(it) } })
        put("visibleCapabilityCount", visibleCount)
      }.toString()
    )
  }

  suspend fun handleManifest(): GatewaySession.InvokeResult =
    dispatchBridge(method = "GET", path = "/manifest", body = "")

  suspend fun handleExecute(paramsJson: String?): GatewaySession.InvokeResult =
    dispatchBridge(method = "POST", path = "/execute", body = paramsJson.orEmpty())

  suspend fun handleGrants(): GatewaySession.InvokeResult =
    dispatchBridge(method = "GET", path = "/grants", body = "")

  suspend fun handleRevoke(paramsJson: String?): GatewaySession.InvokeResult =
    dispatchBridge(method = "POST", path = "/revoke", body = paramsJson.orEmpty())

  private suspend fun dispatchBridge(
    method: String,
    path: String,
    body: String,
  ): GatewaySession.InvokeResult {
    if (!config.enabled.value) {
      return GatewaySession.InvokeResult.error(
        code = "BRIDGE_DISABLED",
        message = "BRIDGE_DISABLED: enable Mobile Bridge in the Bridge tab",
      )
    }

    val token = config.getOrCreateToken()
    val response = server.dispatch(
      MobileBridgeServer.HttpRequest(
        method = method,
        path = path,
        headers = mapOf("authorization" to "Bearer $token"),
        body = body,
      )
    )
    if (response.status in 200..299) {
      return GatewaySession.InvokeResult.ok(response.body)
    }
    return GatewaySession.InvokeResult.error(
      code = "BRIDGE_HTTP_${response.status}",
      message = response.body,
    )
  }
}
