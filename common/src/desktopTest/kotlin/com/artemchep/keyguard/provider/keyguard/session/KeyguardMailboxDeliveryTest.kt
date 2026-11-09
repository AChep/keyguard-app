package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxAddress
import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxState
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeDecoding
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkTestDevice
import com.artemchep.keyguard.provider.keyguard.applink.CountingCrypto
import com.artemchep.keyguard.provider.keyguard.transport.RelayEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a state the relay kept for this device hands to the inbox, see [KeyguardMailbox.deliver]. */
class KeyguardMailboxDeliveryTest {
    @Test
    fun `a state whose roster merge is refused is not delivered`() = realTime {
        val (a, b) = linkedPair()
        val mailbox = mailboxOf(b, b.crypto)

        // Merged into the roster of B, it would hold more devices than a roster may.
        mailbox.deliver(a.deviceId, stateOf(a, b, a.rosterWithMore(Roster.MAX_DEVICES - 1)))
        assertTrue(b.components.inbox.peek(P2pGroupId.APP_LINK_ID).isEmpty)

        mailbox.deliver(a.deviceId, stateOf(a, b, a.localEnvelope().roster))
        val batch = b.components.inbox.peek(P2pGroupId.APP_LINK_ID)
        assertNotNull(batch.roster)
        assertEquals(listOf(a.deviceId), batch.envelopes.map { it.from })
    }

    @Test
    fun `a state with the roster of the gate is delivered without checking it again`() = realTime {
        val (a, b) = linkedPair()
        val crypto = CountingCrypto(b.crypto)
        val mailbox = mailboxOf(b, crypto)
        val running = launch { mailbox.run(flowOf(null), RelayEndpoint("wss://relay.test")) {} }
        val gate = mailbox.gate.filterNotNull().first().roster
        crypto.verifies = 0

        mailbox.deliver(a.deviceId, stateOf(a, b, gate))
        assertEquals(0, crypto.verifies)
        // It is the published roster too: only the envelope waits.
        val batch = b.components.inbox.peek(P2pGroupId.APP_LINK_ID)
        assertNull(batch.roster)
        assertEquals(listOf(a.deviceId), batch.envelopes.map { it.from })
        running.cancel()
    }

    /** Links A and B; A has a forwarder B does not have yet. */
    private suspend fun linkedPair(): Pair<AppLinkTestDevice, AppLinkTestDevice> {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        a.link()
        b.time = a.time + 1
        b.join(a)
        a.addForwarder("Work")
        return a to b
    }

    /** The relay storage of [device], checking signatures with [crypto]. */
    private suspend fun mailboxOf(device: AppLinkTestDevice, crypto: P2pCrypto): KeyguardMailbox {
        val dependencies = device.components.sessionDependencies
        return KeyguardMailbox(
            groupId = P2pGroupId.APP_LINK_ID,
            membership = device.membership(),
            dependencies = KeyguardSessionDependencies(
                roomConnector = dependencies.roomConnector,
                store = dependencies.store,
                compressor = dependencies.compressor,
                crypto = crypto,
                inbox = dependencies.inbox,
                queueSync = dependencies.queueSync,
            ),
            localEnvelope = MutableStateFlow(device.localEnvelope()),
            progress = MutableStateFlow(emptyMap()),
            onReceived = {},
            clock = { 0L },
        )
    }

    /** The state [from] leaves for [to], with the [roster] and the envelope of [from]. */
    private suspend fun stateOf(
        from: AppLinkTestDevice,
        to: AppLinkTestDevice,
        roster: Roster,
    ): MailboxState {
        val envelope = assertIs<EnvelopeDecoding.Valid>(
            EnvelopeCodec.decode(EnvelopeCodec.encode(from.envelope()), to.crypto::sha256),
        )
        return MailboxState(
            address = MailboxAddress(item = "state", from = from.deviceId, to = to.deviceId),
            expiresAt = Long.MAX_VALUE,
            roster = roster,
            envelope = envelope,
            missing = emptyList(),
        )
    }
}
