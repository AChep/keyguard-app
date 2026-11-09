package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.DKeyguardRelayStatus
import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import com.artemchep.keyguard.provider.keyguard.transport.RelayError
import com.artemchep.keyguard.provider.keyguard.transport.TestRelay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Live sessions of two linked apps over an in-memory relay with relay forwarding, real
 * Noise handshakes and the real sync pass on in-memory databases.
 */
class KeyguardSessionManagerTest {
    @Test
    fun `two devices converge after a local edit on either`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)

            // An edit is recorded as it is made: the live session sends it on its own.
            a.addForwarder("Mail")
            eventually { "Mail" in b.forwarders() }

            b.renameForwarder("Mail", "Work mail")
            eventually { a.forwarders() == setOf("Work mail") }

            // Both end up holding the same state, and say so.
            awaitStatus(a) { it.upToDateCount() == 1 }
            awaitStatus(b) { it.upToDateCount() == 1 }
            assertEquals(a.forwarders(), b.forwarders())
            val peerState = a.db.p2pPeerStateQueries
                .getByDeviceId(P2pGroupId.APP_LINK_ID, b.deviceId)
                .executeAsOne()
            assertEquals(DKeyguardTransport.RELAY.storedValue, peerState.lastTransport)
        }
    }

    @Test
    fun `sessions come back after the relay drops them`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)

            group.relay.clients.toList().forEach { it.drop() }
            awaitStatus(a) { status -> status.peers.values.none { it is DKeyguardPeerStatus.Connection.Connected } }
            awaitConnected(a, b)

            a.addForwarder("After the drop")
            eventually { "After the drop" in b.forwarders() }
        }
    }

    @Test
    fun `a pass with no held session syncs with the devices that are online`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            a.addForwarder("From A")
            a.startLive()

            // No one holds B's session: its sync pass connects once.
            b.addForwarder("From B")
            val started = TimeSource.Monotonic.markNow()
            b.sync()
            assertTrue(started.elapsedNow() < KeyguardSessionRegistry.EXCHANGE_DEADLINE_MS.milliseconds)
            // The session ended with the pass.
            assertNull(b.registry.get(P2pGroupId.APP_LINK_ID))

            assertTrue("From A" in b.forwarders())
            eventually { "From B" in a.forwarders() }
        }
    }

    @Test
    fun `a pass with no held session gives up at its deadline`() = realTime {
        val relay = TestRelay().apply { stalled = true }
        KeyguardSessionTestGroup(size = 2, relay = relay, exchangeDeadlineMs = 1_500L).use { group ->
            val b = group.devices[1]
            b.addForwarder("Offline edit")
            val started = TimeSource.Monotonic.markNow()
            // An unreachable relay is not a failed sync.
            b.sync()
            assertTrue(started.elapsedNow() < 5.seconds, "took ${started.elapsedNow()}")
        }
    }

    @Test
    fun `a state too large to send is reported on both devices and the rest still syncs`() = realTime {
        KeyguardSessionTestGroup(size = 2, maxEnvelopeSize = SMALL_ENVELOPE).use { group ->
            val (a, b) = group.devices
            a.fillPastSmallEnvelope()
            b.addForwarder("From B")
            a.startLive()
            b.startLive()
            awaitConnected(a, b)

            // The state of B still arrives; the one of A never leaves, and both say why.
            eventually { "From B" in a.forwarders() }
            awaitStatus(a) { status -> status.tooLarge?.limit == SMALL_ENVELOPE }
            awaitStatus(b) { status ->
                (status.peers[a.deviceId] as? DKeyguardPeerStatus.Connection.Connected)?.tooLarge == true
            }
            assertEquals(setOf("From B"), b.forwarders())
            val tooLarge = assertNotNull(a.status().filterNotNull().first().tooLarge)
            assertTrue(tooLarge.size > SMALL_ENVELOPE)
        }
    }

    @Test
    fun `a pass whose state is too large to send does not wait for its deadline`() = realTime {
        KeyguardSessionTestGroup(size = 2, maxEnvelopeSize = SMALL_ENVELOPE).use { group ->
            val (a, b) = group.devices
            b.addForwarder("From B")
            b.startLive()

            // No one holds A's session: its sync pass connects once.
            a.fillPastSmallEnvelope()
            val started = TimeSource.Monotonic.markNow()
            a.sync()
            assertTrue(started.elapsedNow() < 20.seconds, "took ${started.elapsedNow()}")
            assertTrue("From B" in a.forwarders())
        }
    }

    /** Adds forwarders until the envelope of this device is larger than [SMALL_ENVELOPE]. */
    private suspend fun SessionTestDevice.fillPastSmallEnvelope() {
        var index = 0
        while (EnvelopeCodec.encode(app.envelope()).size <= SMALL_ENVELOPE) {
            addForwarder("Mail ${index++}")
        }
    }

    @Test
    fun `a pass with no held session and no network returns at once`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val b = group.devices[1]
            b.online = false
            b.addForwarder("Offline edit")
            val started = TimeSource.Monotonic.markNow()
            // The pass succeeds.
            b.sync()
            assertTrue(started.elapsedNow() < 2.seconds, "took ${started.elapsedNow()}")
            // The edit waits for the next exchange.
            assertTrue(b.app.forwarderSyncId("Offline edit") in b.app.entries().keys.map { it.id })

            // An unreachable relay fails the attempt quickly too.
            b.online = true
            group.relay.reachable = false
            val again = TimeSource.Monotonic.markNow()
            b.sync()
            assertTrue(again.elapsedNow() < 5.seconds, "took ${again.elapsedNow()}")
        }
    }

    @Test
    fun `a relay this build can not use is reported as such`() = realTime {
        val relay = TestRelay().apply {
            reachable = false
            refusal = RelayError.Incompatible
        }
        KeyguardSessionTestGroup(size = 2, relay = relay).use { group ->
            val a = group.devices[0]
            a.startLive()
            awaitStatus(a) { it.relay == DKeyguardRelayStatus.State.INCOMPATIBLE }
            // Not the same as a relay out of reach.
            relay.refusal = RelayError.Network
            awaitStatus(a) { it.relay == DKeyguardRelayStatus.State.FAILED }
        }
    }

    private companion object {
        /** Room for the roster and a forwarder or two. */
        const val SMALL_ENVELOPE = 4 * 1024
    }
}
