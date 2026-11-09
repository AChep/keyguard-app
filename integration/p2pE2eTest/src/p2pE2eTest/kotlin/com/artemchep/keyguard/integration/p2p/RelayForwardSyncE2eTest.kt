package com.artemchep.keyguard.integration.p2p

import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.integration.p2p.P2pE2eDevice.Companion.API_KEY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Apps link and sync through a real relay with relay forwarding only, as Wear OS and the
 * AutoFill extension do: every byte of every session goes through the relay.
 */
class RelayForwardSyncE2eTest {
    @Test
    fun `two apps converge on edits made on both`() = p2pScenario { testbed ->
        val a = testbed.device("Desktop", Transports.relayOnly())
        val b = testbed.device("Laptop", Transports.relayOnly())
        // Forwarders from before the link, one of them set up on both apps.
        val mailKey = mapOf(API_KEY to "mail-key")
        val mail = a.addForwarder("Mail", data = mailKey)
        val bank = a.addForwarder("Bank", type = "DuckDuckGo")
        b.addForwarder("Mail", data = mailKey)
        val shop = b.addForwarder("Shop", type = "AnonAddy")
        link(member = a, joiner = b)
        // The enrollment carried the forwarders of A; B adopted its copy of one of them.
        val joined = awaitConverged(a, b)
        assertEquals(listOf("Bank", "Mail", "Shop"), joined.names())
        assertEquals(setOf(mail, bank, shop), joined.forwarders.keys)

        // Different parts of one forwarder, and different forwarders, edited on both sides.
        a.editForwarder(mail, name = "Personal mail")
        b.editForwarder(mail, data = mapOf(API_KEY to "rotated"))
        val travel = b.addForwarder("Travel", type = "Fastmail")
        a.editForwarder(shop, name = "Shopping")
        val edited = awaitConverged(a, b)
        assertEquals(Forwarder("Personal mail", "SimpleLogin", mapOf(API_KEY to "rotated")), edited.forwarder(mail))
        assertEquals("Fastmail", edited.forwarder(travel)?.type)
        assertEquals("Shopping", edited.forwarder(shop)?.name)

        // A delete on each side.
        a.removeForwarder(bank)
        b.removeForwarder(shop)
        val removed = awaitConverged(a, b)
        assertEquals(setOf(mail, travel), removed.forwarders.keys)
        // Deletes stay as tombstones, so an app that missed them can not bring them back.
        assertTrue(removed.entries.getValue(bank).deleted)
        assertTrue(removed.entries.getValue(shop).deleted)
        assertFalse(removed.entries.getValue(mail).deleted)

        awaitTransport(a, b, DKeyguardTransport.RELAY)
    }

    @Test
    fun `three apps converge when the third joins through the second`() = p2pScenario { testbed ->
        val a = testbed.device("Desktop", Transports.relayOnly())
        val note = a.addForwarder("Note")
        val b = testbed.device("Laptop", Transports.relayOnly())
        link(member = a, joiner = b)
        // Any active device admits new ones; the founder learns about it from the next exchange.
        val c = testbed.device("Phone", Transports.relayOnly())
        link(member = b, joiner = c)

        val joined = awaitConverged(a, b, c)
        val ids = setOf(a.deviceId(), b.deviceId(), c.deviceId())
        assertEquals(ids, joined.activeDevices())
        assertEquals(setOf(note), joined.forwarders.keys)

        val fromA = a.addForwarder("From desktop")
        val fromB = b.addForwarder("From laptop")
        val fromC = c.addForwarder("From phone")
        c.editForwarder(note, name = "Shared note")
        val edited = awaitConverged(a, b, c)
        assertEquals("From desktop", edited.forwarder(fromA)?.name)
        assertEquals("From laptop", edited.forwarder(fromB)?.name)
        assertEquals("From phone", edited.forwarder(fromC)?.name)
        assertEquals("Shared note", edited.forwarder(note)?.name)

        // Every pair holds a session of its own.
        awaitTransport(a, b, DKeyguardTransport.RELAY)
        awaitTransport(b, c, DKeyguardTransport.RELAY)
        awaitTransport(a, c, DKeyguardTransport.RELAY)
    }
}
