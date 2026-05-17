package com.openclaw.assistant.bridge

import com.openclaw.assistant.bridge.grants.BridgeGrants
import com.openclaw.assistant.bridge.grants.DestructiveVerbs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeGrantsTest {

    @After fun tearDown() { BridgeGrants.revokeAll() }

    @Test fun `grant within TTL is honoured`() {
        BridgeGrants.grant("apps.launch", 60_000L)
        assertTrue(BridgeGrants.isGranted("apps.launch"))
    }

    @Test fun `grant past TTL is not honoured`() {
        BridgeGrants.grant("apps.launch", 1L)
        Thread.sleep(10L)
        assertFalse(BridgeGrants.isGranted("apps.launch"))
    }

    @Test fun `revoke drops single grant`() {
        BridgeGrants.grant("a", 60_000L); BridgeGrants.grant("b", 60_000L)
        BridgeGrants.revoke("a")
        assertFalse(BridgeGrants.isGranted("a"))
        assertTrue(BridgeGrants.isGranted("b"))
    }

    @Test fun `revokeAll empties store`() {
        BridgeGrants.grant("a", 60_000L); BridgeGrants.grant("b", 60_000L)
        BridgeGrants.revokeAll()
        assertEquals(0, BridgeGrants.snapshot().size)
    }

    @Test fun `permanent grant has very far expiry`() {
        BridgeGrants.grant("apps.launch", Long.MAX_VALUE)
        assertTrue(BridgeGrants.isGranted("apps.launch"))
        assertTrue(BridgeGrants.snapshot().first().expiresAtMs == Long.MAX_VALUE)
    }

    @Test fun `destructive verb detection is case insensitive substring`() {
        assertTrue(DestructiveVerbs.isDestructive("contacts.delete"))
        assertTrue(DestructiveVerbs.isDestructive("CONTACTS.DELETE"))
        assertTrue(DestructiveVerbs.isDestructive("sms.send"))
        assertFalse(DestructiveVerbs.isDestructive("device.info"))
        assertFalse(DestructiveVerbs.isDestructive("apps.list"))
    }
}
