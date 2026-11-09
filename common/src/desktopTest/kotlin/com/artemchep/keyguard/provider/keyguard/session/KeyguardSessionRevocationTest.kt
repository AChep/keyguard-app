package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayDefaults
import com.artemchep.keyguard.provider.keyguard.transport.NoiseByteStream
import com.artemchep.keyguard.provider.keyguard.transport.NoiseSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A device removed from the link, online or offline at the time, learns it from the
 * device that removed it, and nothing it sends is applied. Live sessions over an in-memory
 * relay with relay forwarding, or direct streams that drop silently, real Noise handshakes
 * and the real sync pass.
 *
 * The links also include an offline member: notification must work without every
 * member being online.
 */
// One function per scenario, plus the scripted peers.
@Suppress("TooManyFunctions")
class KeyguardSessionRevocationTest {
    @Test
    fun `a revoked device is cut off and learns that it was removed`() = realTime {
        KeyguardSessionTestGroup(size = 3).use { group ->
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)

            group.revoke(b)
            a.sync()

            awaitStatus(a) { b.deviceId !in it.peers }
            awaitStatus(b) { it.revoked }
            eventually { b.state() == DAppLinkState.Removed }
            // The removed device gets nothing new.
            a.addForwarder("Secret")
            a.sync()
            delay(QUIET_MS)
            assertFalse("Secret" in b.forwarders())
        }
    }

    @Test
    fun `a device revoked while offline learns it once it dials`() = revokedWhileOffline(revokedDials = true)

    @Test
    fun `a device revoked while offline learns it once it is dialed`() = revokedWhileOffline(revokedDials = false)

    /** The device with the smaller id dials; [revokedDials] picks which one is removed. */
    private fun revokedWhileOffline(revokedDials: Boolean) = realTime {
        val (low, high) = List(2) { newTestDevice("Device $it") }.sortedBy { it.deviceId }
        val identities = if (revokedDials) listOf(high, low) else listOf(low, high)
        KeyguardSessionTestGroup(size = 3, identities = identities + newTestDevice("Device 2")).use { group ->
            val (a, b) = group.devices
            a.startLive()
            val offline = b.startLive()
            awaitConnected(a, b)
            offline.cancelAndJoin()
            awaitStatus(a) { status -> status.peers[b.deviceId] !is DKeyguardPeerStatus.Connection.Connected }

            group.revoke(b)
            a.sync()
            awaitStatus(a) { b.deviceId !in it.peers }
            // An edit of the removed device, made before it knows.
            b.addForwarder("From the removed device")

            // It still lists A, and dials it or waits for it: A tells it.
            b.startLive()
            awaitStatus(b) { it.revoked }
            eventually { b.state() == DAppLinkState.Removed }
            // Nothing it sent was applied.
            delay(QUIET_MS)
            assertFalse("From the removed device" in a.forwarders())
            // Both left the room of their pair: A stays only in the room it shares with the
            // third device, which the removed one can not find.
            eventually { group.relay.clients.count { it.idOrNull != null } == 1 }
        }
    }

    /**
     * Something answers A in the room of the pair as B, and hangs up once it read the first
     * frame, as a session of B that closes does: that tells B nothing, so A tells B again
     * once it is back. B has the larger id, so A dials it.
     */
    @Test
    fun `a notice that no session of the removed device answered is sent again`() = realTime {
        val (low, high) = List(2) { newTestDevice("Device $it") }.sortedBy { it.deviceId }
        KeyguardSessionTestGroup(size = 3, identities = listOf(low, high, newTestDevice("Device 2"))).use { group ->
            val (a, b) = group.devices
            a.startLive()
            group.revoke(b)
            a.sync()
            awaitStatus(a) { b.deviceId !in it.peers }

            group.joinPairRoom(self = b, peer = a) { room ->
                room.signaling.presence.first { a.deviceId in it }
                val selected = room.connect(a.deviceId, ConnectRole.RESPONDER)
                val stream = noiseHandshake(selected.stream, self = b, peer = a, initiator = false)
                stream.read()
                stream.close()
            }

            b.startLive()
            awaitStatus(b) { it.revoked }
            eventually { b.state() == DAppLinkState.Removed }
        }
    }

    /**
     * Something shows up in the room of the pair as B, and leaves before it answers A, as
     * often as A may fail to tell B: visits that end first do not count, so A still tells B
     * once it is back.
     */
    @Test
    fun `a device that leaves before it answers is told on a later visit`() = realTime {
        val (low, high) = List(2) { newTestDevice("Device $it") }.sortedBy { it.deviceId }
        KeyguardSessionTestGroup(size = 3, identities = listOf(low, high, newTestDevice("Device 2"))).use { group ->
            val (a, b) = group.devices
            a.startLive()
            group.revoke(b)
            a.sync()
            awaitStatus(a) { b.deviceId !in it.peers }

            repeat(KeyguardRevokedPeers.MAX_ATTEMPTS) {
                group.joinPairRoom(self = b, peer = a) { room ->
                    room.signaling.presence.first { a.deviceId in it }
                    // Longer than A waits between failed attempts, so A dials it.
                    delay(VISIT_MS)
                }
            }

            b.startLive()
            awaitStatus(b) { it.revoked }
            eventually { b.state() == DAppLinkState.Removed }
        }
    }

    /**
     * Something shows up in the room of the pair as B, which has the smaller id and so
     * dials, and leaves without dialing: A stops waiting for it at once, and tells B on its
     * next visit, rather than once the wait for the one that left gave up.
     */
    @Test
    fun `a device that leaves before it dials is told on its next visit at once`() = realTime {
        val (low, high) = List(2) { newTestDevice("Device $it") }.sortedBy { it.deviceId }
        KeyguardSessionTestGroup(size = 3, identities = listOf(high, low, newTestDevice("Device 2"))).use { group ->
            val (a, b) = group.devices
            a.startLive()
            group.revoke(b)
            a.sync()
            awaitStatus(a) { b.deviceId !in it.peers }

            group.joinPairRoom(self = b, peer = a) { room ->
                room.signaling.presence.first { a.deviceId in it }
                delay(LEAVE_MS)
            }

            val back = TimeSource.Monotonic.markNow()
            b.startLive()
            awaitStatus(b) { it.revoked }
            val elapsed = back.elapsedNow()
            assertTrue(elapsed < NEXT_VISIT_TIMEOUT, "B was told after $elapsed")
        }
    }

    /**
     * B waits to be dialed by C, which is in the room of their pair, and closes its sessions
     * for good, as when the app goes away: the connection C opens then finds no one. C has
     * the smaller id, so it dials.
     */
    @Test
    fun `a device that closes its sessions for good takes no new connection`() = realTime {
        val devices = List(3) { newTestDevice("Device $it") }.sortedBy { it.deviceId }
        val (lowest, middle, highest) = devices
        // The founder, B and C.
        KeyguardSessionTestGroup(size = 3, identities = listOf(middle, highest, lowest)).use { group ->
            val (_, b, c) = group.devices
            b.startLive()
            group.joinPairRoom(self = c, peer = b) { room ->
                awaitStatus(b) { it.peers[c.deviceId] == DKeyguardPeerStatus.Connection.Connecting }
                checkNotNull(b.registry.get(P2pGroupId.APP_LINK_ID)).closeSessions()

                val accepted = withTimeoutOrNull(ACCEPT_WAIT_MS) {
                    val selected = room.connect(b.deviceId, ConnectRole.INITIATOR)
                    noiseHandshake(selected.stream, self = c, peer = b, initiator = true).close()
                }
                assertNull(accepted)
            }
        }
    }

    /** Joins the room of the pair of [self] and [peer] as [self], with no session, and runs [block] there. */
    private suspend fun <T> KeyguardSessionTestGroup.joinPairRoom(
        self: SessionTestDevice,
        peer: SessionTestDevice,
        block: suspend CoroutineScope.(KeyguardRoom) -> T,
    ): T {
        val connector = testRoomConnector(relay.connector)
        val room = connector.pairRoom(
            groupKey = checkNotNull(P2pEncoding.base64UrlOrNull(groupKeyBase64)),
            scope = SyncScope.APP,
            localNoisePrivate = self.noisePrivateKey(),
            localNoisePub = self.noisePublicKey(),
            peerNoisePub = peer.noisePublicKey(),
            localKemPrivate = checkNotNull(P2pEncoding.base64UrlOrNull(self.membership.device.kemPrivateKeyBase64)),
            peerKemPub = P2pEncoding.base64UrlOrNull(peer.membership.device.kemPublicKeyBase64),
        )
        val spec = KeyguardRoomSpec(
            endpoint = KeyguardRelayDefaults.endpoint(self.membership.network),
            room = room,
            localDeviceId = self.deviceId,
            allowLocalNetwork = true,
        )
        return connector.join(spec) { joined -> block(joined) }
    }

    /** Runs the Noise KKhfs handshake of the sessions as [self] with [peer] over [stream]. */
    private suspend fun noiseHandshake(
        stream: ByteStream,
        self: SessionTestDevice,
        peer: SessionTestDevice,
        initiator: Boolean,
    ): NoiseByteStream = NoiseByteStream.handshake(
        stream = stream,
        suite = NoiseSuite.KK_HFS,
        initiator = initiator,
        localPrivateKey = self.noisePrivateKey(),
        remotePublicKey = peer.noisePublicKey(),
    )

    private fun SessionTestDevice.noisePrivateKey(): ByteArray =
        checkNotNull(P2pEncoding.base64UrlOrNull(membership.device.noisePrivateKeyBase64))

    private fun SessionTestDevice.noisePublicKey(): ByteArray =
        checkNotNull(P2pEncoding.base64UrlOrNull(membership.device.noisePublicKeyBase64))

    @Test
    fun `a device revoked before its peer noticed it leave learns it once it dials`() =
        revokedUnnoticed(revokedDials = true, awayMs = AWAY_MS)

    @Test
    fun `a device revoked before its peer noticed it leave learns it once it is dialed`() =
        revokedUnnoticed(revokedDials = false, awayMs = AWAY_MS)

    @Test
    fun `a device revoked before its peer noticed it leave learns it when it is back at once`() =
        revokedUnnoticed(revokedDials = false, awayMs = 0L)

    /**
     * B leaves over a connection that drops silently, so A's session with it still runs when
     * A removes it: the roster and BYE go out to no one. B comes back [awayMs] after A's
     * sync pass. A device that dials is never back at once here: its new connection could
     * replace the session before the session ends.
     */
    private fun revokedUnnoticed(revokedDials: Boolean, awayMs: Long) = realTime {
        val switchboard = SilentSwitchboard()
        val (low, high) = List(2) { newTestDevice("Device $it") }.sortedBy { it.deviceId }
        val identities = if (revokedDials) listOf(high, low) else listOf(low, high)
        KeyguardSessionTestGroup(
            size = 3,
            timings = SLOW_DEATH,
            transportFactory = switchboard.factory,
            identities = identities + newTestDevice("Device 2"),
        ).use { group ->
            val (a, b) = group.devices
            a.startLive()
            // A session that drops without a word, unlike a held one: A never hears that it ended.
            val offline = b.startBareSession()
            awaitStatus(a) { status -> status.peers[b.deviceId] is DKeyguardPeerStatus.Connection.Connected }
            offline.cancelAndJoin()

            group.revoke(b)
            a.sync()
            awaitStatus(a) { b.deviceId !in it.peers }
            delay(awayMs)

            b.startLive()
            awaitStatus(b) { it.revoked }
            eventually { b.state() == DAppLinkState.Removed }
        }
    }

    private companion object {
        const val QUIET_MS = 2_000L

        /** Longer than the grace A gives its session with a removed device: A waits for B. */
        const val AWAY_MS = KeyguardRevokedPeers.SESSION_GRACE_MS + 1_000L

        /**
         * How long something stays in a room as B once A is there: longer than A waits after
         * its second failed attempt, 2 s with jitter, so A dials each visit.
         */
        const val VISIT_MS = 3_000L

        /** How long a new connection waits to be taken. */
        const val ACCEPT_WAIT_MS = 3_000L

        /** How long something stays in a room as B once A is there, before it leaves. */
        const val LEAVE_MS = 1_000L

        /** Well within the 30 s that a wait for a device that left takes to give up. */
        val NEXT_VISIT_TIMEOUT = 15.seconds
    }
}
