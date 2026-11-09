package com.artemchep.keyguard.integration.p2p

import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DKeyguardRelayStatus
import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Who syncs: an app removed from the link, online or offline at the time, and an app
 * without a live session.
 *
 * Includes two-device links: a member left alone must still notify the removed peer.
 */
class MembershipSyncE2eTest {
    @Test
    fun `removing the only peer tells it over relay forwarding`() = p2pScenario { testbed ->
        val a = testbed.device("Desktop", Transports.relayOnly())
        val b = testbed.device("Phone", Transports.relayOnly())
        link(member = a, joiner = b)
        awaitConverged(a, b)
        awaitConnected(a, b)
        a.revoke(b.deviceId())
        awaitLinkState(b, DAppLinkState.Removed)
        awaitLinkState(a, DAppLinkState.Alone)
    }

    @Test
    fun `the only peer removed offline learns it once it returns`() = p2pScenario { testbed ->
        val a = testbed.device("Desktop", Transports.webRtc())
        val b = testbed.device("Phone", Transports.webRtc())
        link(member = a, joiner = b)
        awaitConverged(a, b)
        awaitConnected(a, b)
        awaitTransport(a, b, DKeyguardTransport.LAN)
        b.stopLive()
        a.revoke(b.deviceId())
        awaitLinkState(a, DAppLinkState.Alone)
        b.startLive()
        awaitLinkState(b, DAppLinkState.Removed)
    }

    @Test
    fun `unlink tells the connected peer over relay forwarding`() = p2pScenario { testbed ->
        val a = testbed.device("Desktop", Transports.relayOnly())
        val b = testbed.device("Phone", Transports.relayOnly())
        link(member = a, joiner = b)
        awaitConverged(a, b)
        awaitConnected(a, b)
        b.unlink()
        awaitLinkState(a, DAppLinkState.Alone)
        assertEquals(DAppLinkState.NotLinked, b.linkState())
    }

    @Test
    fun `unlink tells both connected peers over WebRTC`() = p2pScenario { testbed ->
        val a = testbed.device("Desktop", Transports.webRtc())
        val b = testbed.device("Laptop", Transports.webRtc())
        val c = testbed.device("Phone", Transports.webRtc())
        link(member = a, joiner = b)
        link(member = a, joiner = c)
        awaitConverged(a, b, c)
        awaitConnected(a, b)
        awaitConnected(b, c)
        awaitTransport(a, b, DKeyguardTransport.LAN)
        awaitTransport(b, c, DKeyguardTransport.LAN)
        b.unlink()
        awaitLinkState(a, DAppLinkState.Linked(1))
        awaitLinkState(c, DAppLinkState.Linked(1))
        assertEquals(DAppLinkState.NotLinked, b.linkState())
    }

    @Test
    fun `a removed app is cut off and its edits no longer arrive`() = p2pScenario { testbed ->
        val a = testbed.device("Desktop", Transports.relayOnly())
        val mail = a.addForwarder("Mail")
        val b = testbed.device("Laptop", Transports.relayOnly())
        val c = testbed.device("Phone", Transports.relayOnly())
        link(member = a, joiner = b)
        link(member = a, joiner = c)
        awaitConverged(a, b, c)
        // The running session tells B.
        awaitConnected(a, b)
        val bId = b.deviceId()

        a.revoke(bId)

        // The session tells B before it goes; B's next pass finds itself removed.
        awaitLinkState(b, DAppLinkState.Removed)
        withTimeout(STATUS_TIMEOUT) {
            a.status().filterNotNull().first { bId !in it.peers }
            c.status().filterNotNull().first { bId !in it.peers }
        }
        // A removed app has no one to sync with.
        eventually("the removed app stops its live session") { !b.isLive() }
        assertEquals(DAppLinkState.Linked(peers = 1), a.linkState())
        assertFalse(bId in a.snapshot().activeDevices())

        // Neither side's edits reach the other any more.
        b.editForwarder(mail, name = "Renamed after removal")
        b.sync()
        val secret = a.addForwarder("Secret")
        assertEquals("Secret", awaitConverged(a, c).forwarder(secret)?.name)
        delay(QUIET_MS)
        assertEquals("Mail", a.snapshot().forwarder(mail)?.name)
        assertEquals("Mail", c.snapshot().forwarder(mail)?.name)
        assertNull(b.snapshot().forwarder(secret))
    }

    @Test
    fun `an app removed while offline learns it once it comes back`() = p2pScenario { testbed ->
        val a = testbed.device("Desktop", Transports.webRtc())
        val mail = a.addForwarder("Mail")
        val b = testbed.device("Laptop", Transports.webRtc())
        val c = testbed.device("Phone", Transports.webRtc())
        link(member = a, joiner = b)
        link(member = a, joiner = c)
        awaitConverged(a, b, c)
        awaitConnected(a, b)
        val bId = b.deviceId()

        b.stopLive()
        a.revoke(bId)
        withTimeout(STATUS_TIMEOUT) {
            a.status().filterNotNull().first { bId !in it.peers }
        }
        eventually("the phone learns that the laptop was removed") {
            bId !in c.snapshot().activeDevices()
        }
        // An edit B makes before it knows.
        b.editForwarder(mail, name = "Renamed while removed")

        // B still lists the others and connects to them; they tell B, and take nothing from it.
        b.startLive()
        awaitLinkState(b, DAppLinkState.Removed)
        delay(QUIET_MS)
        assertEquals("Mail", a.snapshot().forwarder(mail)?.name)
        assertEquals("Mail", c.snapshot().forwarder(mail)?.name)
        assertFalse(bId in a.snapshot().activeDevices())
    }

    @Test
    fun `an app without a live session syncs through a one-shot exchange`() = p2pScenario { testbed ->
        val a = testbed.device("Desktop", Transports.relayOnly())
        a.addForwarder("Mail")
        // Like Android's background sync: no live session, one exchange per pass.
        val b = testbed.device("Phone", Transports.relayOnly(), live = false)
        link(member = a, joiner = b)
        val fromA = a.addForwarder("From desktop")
        val fromB = b.addForwarder("From phone")
        // The exchange meets the devices that are online, with the state their live
        // sessions hold: those pick up a local edit once the edits settle.
        withTimeout(STATUS_TIMEOUT) {
            a.status().filterNotNull().first { it.relay == DKeyguardRelayStatus.State.CONNECTED }
        }
        delay(KeyguardSessionManager.CHANGE_DEBOUNCE_MS + EDIT_PICKUP_MARGIN_MS)

        b.sync()
        // The pass merged what the desktop sent while they were connected.
        val synced = awaitConverged(a, b)
        assertEquals("From desktop", synced.forwarder(fromA)?.name)
        assertEquals("From phone", synced.forwarder(fromB)?.name)
        assertFalse(b.isLive())
        assertEquals(DAppLinkState.Linked(peers = 1), b.linkState())
        awaitTransport(a, b, DKeyguardTransport.RELAY)
    }

    private companion object {
        val STATUS_TIMEOUT = 30.seconds

        /** How long nothing must arrive. */
        const val QUIET_MS = 5_000L

        /** How long a live session may take to rebuild its state once the edits settled. */
        const val EDIT_PICKUP_MARGIN_MS = 2_000L
    }
}
