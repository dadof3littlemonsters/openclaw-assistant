package com.openclaw.assistant.bridge

import android.content.Context
import com.openclaw.assistant.backend.InMemorySharedPreferences
import com.openclaw.assistant.node.MobileBridgeHandler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MobileBridgeHandlerTest {
  private lateinit var context: Context
  private lateinit var config: MobileBridgeConfig
  private lateinit var handler: MobileBridgeHandler

  @Before
  fun setUp() {
    context = mockk(relaxed = true)
    every { context.packageName } returns "com.openclaw.assistant.test"
    config = MobileBridgeConfig(InMemorySharedPreferences())
    config.setAllowedCapabilityGroups(setOf("device"))
    config.getOrCreateToken()
    handler = MobileBridgeHandler(
      context = context,
      config = config,
      registry = BridgeRegistry(listOf(StubBridgeDeviceCap)),
    )
  }

  @Test
  fun `status works even when bridge server is disabled`() {
    config.setEnabled(false)

    val result = handler.handleStatus()

    assertEquals(true, result.ok)
    assertTrue(result.payloadJson!!.contains("\"enabled\":false"))
    assertTrue(result.payloadJson!!.contains("\"visibleCapabilityCount\":1"))
  }

  @Test
  fun `manifest returns disabled error until user enables bridge`() = runTest {
    config.setEnabled(false)

    val result = handler.handleManifest()

    assertEquals(false, result.ok)
    assertEquals("BRIDGE_DISABLED", result.error?.code)
  }

  @Test
  fun `execute reuses mobile bridge capability registry`() = runTest {
    config.setEnabled(true)

    val result = handler.handleExecute("""{"requestId":"r1","capability":"device.info","arguments":{}}""")

    assertEquals(true, result.ok)
    assertTrue(result.payloadJson!!.contains("\"requestId\":\"r1\""))
    assertTrue(result.payloadJson!!.contains("\"status\":\"completed\""))
    assertTrue(result.payloadJson!!.contains("\"ok\":true"))
  }
}

private object StubBridgeDeviceCap : BridgeCapability {
  override val name = "device.info"
  override val description = "stub"
  override val group = "device"
  override val riskLevel = RiskLevel.LOW

  override suspend fun execute(context: Context, arguments: JsonObject) =
    buildJsonObject { put("ok", JsonPrimitive(true)) }
}
