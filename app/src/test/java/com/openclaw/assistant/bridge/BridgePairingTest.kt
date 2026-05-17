package com.openclaw.assistant.bridge

import com.openclaw.assistant.backend.InMemorySharedPreferences
import com.openclaw.assistant.bridge.pairing.BridgePairing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePairingTest {

    @After fun cleanup() { BridgePairing.cancel() }

    @Test fun `offer has 6 char code and embeds payload`() {
        val offer = BridgePairing.createOffer("http://192.168.1.42:8787")
        assertEquals(6, offer.code.length)
        assertTrue(offer.qrPayload.startsWith("agentvoice://pair?"))
        assertTrue(offer.qrPayload.contains("c=${offer.code}"))
    }

    @Test fun `creating second offer cancels first`() {
        val a = BridgePairing.createOffer("http://h")
        val b = BridgePairing.createOffer("http://h")
        assertNotNull(BridgePairing.currentOffer())
        // a is gone, b is current
        val cfg = MobileBridgeConfig(InMemorySharedPreferences()).also { it.getOrCreateToken() }
        assertNull(BridgePairing.redeem(a.code, cfg))
        assertNotNull(BridgePairing.redeem(b.code, cfg))
    }

    @Test fun `redeem is one-shot`() {
        val cfg = MobileBridgeConfig(InMemorySharedPreferences()).also { it.getOrCreateToken() }
        val offer = BridgePairing.createOffer("http://h")
        val t1 = BridgePairing.redeem(offer.code, cfg)
        val t2 = BridgePairing.redeem(offer.code, cfg)
        assertNotNull(t1)
        assertNull(t2)
    }

    @Test fun `redeem returns the bridge token`() {
        val cfg = MobileBridgeConfig(InMemorySharedPreferences())
        val expected = cfg.getOrCreateToken()
        val offer = BridgePairing.createOffer("http://h")
        assertEquals(expected, BridgePairing.redeem(offer.code, cfg))
    }

    @Test fun `code is case insensitive on redeem`() {
        val cfg = MobileBridgeConfig(InMemorySharedPreferences()).also { it.getOrCreateToken() }
        val offer = BridgePairing.createOffer("http://h")
        // Codes are uppercase; lowercase submission still works.
        assertNotNull(BridgePairing.redeem(offer.code.lowercase(), cfg))
    }
}
