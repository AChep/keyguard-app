package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.util.ReconnectBackoff
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.feature.p2psync.session.Frame
import com.artemchep.keyguard.feature.p2psync.session.FrameCodec
import com.artemchep.keyguard.feature.p2psync.session.SessionEffect
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionStateMachine
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells the devices the roster removed that they were removed: a device removed while
 * offline still dials the devices it knows, which no longer dial it back. The notice is
 * HELLO, the roster and BYE. It counts once a session of the device answers with a frame of
 * its own, which is not read further: something else may take the connection, such as a
 * session of the device that closes, and read nothing.
 *
 * Each notice keeps this device in the room of the pair with the removed one, where the
 * removed device sees when this one is online. So a device is told without direct
 * connections, which would show it the network addresses of this one, within
 * [TELL_WINDOW_MS] of its removal and in at most [MAX_ATTEMPTS] failed attempts; then this
 * device leaves the room for good. An attempt ends once its visit does, such as when a
 * session of the device that closes leaves the room: the next visit is tried at once, and
 * up to [MAX_VISITS_LEFT] such attempts do not count as failed.
 *
 * A visit is one relay connection of the device, named by its relay peer id: a device that
 * comes back joins under a new one, and is told again.
 *
 * @property settled the removed devices that need no notice anymore: told by a notice, or
 *   given up on.
 * @property toldVisits the visit in which the session that ran when a device was removed
 *   told it, by device id: a notice over a connection that died unnoticed reaches no one,
 *   so the device is told again once it comes back under another visit.
 */
internal class KeyguardRevokedPeers(
    private val membership: P2pMembership,
    private val dependencies: KeyguardSessionDependencies,
    /** A monotonic clock in milliseconds. */
    private val clock: () -> Long,
    /** Authenticates a stream to the device as the initiator, or else as the responder. */
    private val handshake: suspend (stream: ByteStream, peer: RosterDevice, initiator: Boolean) -> ByteStream,
    /** Returns the latest local roster. */
    private val roster: suspend () -> Roster,
    val settled: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
    val toldVisits: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
) {
    companion object {
        /** The session that runs with a device that was just removed gets this long to tell it. */
        const val SESSION_GRACE_MS: Long = 3_000L

        /** 7 days: how long after its removal a device is told. */
        const val TELL_WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1_000L

        /** How many attempts to tell a removed device that shows up may fail. */
        const val MAX_ATTEMPTS: Int = 3

        /**
         * How many attempts whose visit ended first are tried again at once; then they count
         * as failed. Each needs the device to join the room anew, and a removed device that
         * keeps leaving must not keep this one in the room for the whole window.
         */
        const val MAX_VISITS_LEFT: Int = 10

        /** The longest pause between two attempts to tell a removed device. */
        private const val MAX_RETRY_MS: Long = 60_000L

        /**
         * How long one attempt to tell a removed device may take once it shows up: a device
         * that connects, then stops reading, must not keep this one in the room.
         */
        const val TELL_TIMEOUT_MS: Long = 60_000L

        /**
         * How long a told device gets to answer the notice: its session sends its HELLO
         * right after the handshake.
         */
        const val ANSWER_TIMEOUT_MS: Long = 10_000L
    }

    /** How an attempt to tell a removed device ended. */
    private enum class Outcome {
        /** The device was told, or the window closed: nothing is left to do. */
        DONE,

        /** The device was not told; this counts against [MAX_ATTEMPTS]. */
        FAILED,

        /** The visit the attempt was for ended first: the next one is tried at once. */
        LEFT,
    }

    private val self get() = membership.device.deviceId

    /**
     * The removed devices of [roster] but this one that still need a notice: removed less
     * than [TELL_WINDOW_MS] ago, by the stamp of the removal, and not [settled].
     */
    fun of(roster: Roster): Map<String, RosterDevice> {
        val validated = RosterValidator(dependencies.crypto, membership.groupId).validate(roster)
        return validated.revokedPeersToNotify(self, dependencies.crypto, dependencies.now().toEpochMilliseconds())
            .filter { it.id !in settled.value }
            .associateBy { it.id }
    }

    /**
     * Tells [peer], removed, once it shows up in its [room]; returns once it was told, or
     * once this device gives up on it, see [settled].
     */
    suspend fun notify(room: KeyguardRoom, peer: RosterDevice) {
        val initiator = self < peer.id
        val backoff = ReconnectBackoff(maxDelayMs = MAX_RETRY_MS)
        val validated = RosterValidator(dependencies.crypto, membership.groupId).validate(roster())
        val windowEnd = RosterActivity(validated, dependencies.crypto::sha256)
            .revokedAt(peer.id)
            ?.let { it.wall + TELL_WINDOW_MS }
            ?: 0L
        var failures = 0
        var visitsLeft = 0
        var outcome: Outcome? = null
        while (outcome != Outcome.DONE && failures < MAX_ATTEMPTS) {
            outcome = attempt(room, peer, initiator, windowEnd)
            // A visit that ended first is tried again at once, as long as such visits are few.
            val again = outcome == Outcome.LEFT && ++visitsLeft <= MAX_VISITS_LEFT
            if (outcome != Outcome.DONE && !again) {
                failures += 1
                if (failures < MAX_ATTEMPTS) delay(backoff.nextDelayMs())
            }
        }
        settled.update { it + peer.id }
    }

    /**
     * Waits for a visit of [peer] in its [room] that was not told yet, until [windowEnd] in
     * milliseconds since the epoch, and tells it within [TELL_TIMEOUT_MS], and never past
     * [windowEnd].
     */
    private suspend fun attempt(
        room: KeyguardRoom,
        peer: RosterDevice,
        initiator: Boolean,
        windowEnd: Long,
    ): Outcome {
        val left = windowEnd - dependencies.now().toEpochMilliseconds()
        val visit = if (left > 0) {
            withTimeoutOrNull(left) {
                combine(room.signaling.relayPeers, room.relay.presence) { visits, present ->
                    visits[peer.id]?.takeIf { it in present && it != toldVisits.value[peer.id] }
                }.filterNotNull().first()
            }
        } else {
            null
        }
        val timeout = minOf(TELL_TIMEOUT_MS, windowEnd - dependencies.now().toEpochMilliseconds())
        return if (visit == null || timeout <= 0L) {
            Outcome.DONE
        } else {
            tellVisit(room, peer, initiator, visit, timeout)
        }
    }

    /**
     * Tells the [visit] of [peer] within [timeout], see [tell]; stops once the visit ends,
     * since a connection to it would wait in vain.
     */
    private suspend fun tellVisit(
        room: KeyguardRoom,
        peer: RosterDevice,
        initiator: Boolean,
        visit: String,
        timeout: Long,
    ): Outcome {
        val told = withTimeoutOrNull(timeout) {
            coroutineScope {
                val telling = async { tell(room, peer, initiator) }
                val gone = async { room.relay.presence.first { visit !in it } }
                select<Boolean?> {
                    telling.onAwait { it }
                    gone.onAwait { null }
                }.also {
                    telling.cancel()
                    gone.cancel()
                }
            }
        }
        return when {
            told == true -> Outcome.DONE
            visit !in room.relay.presence.value -> Outcome.LEFT
            else -> Outcome.FAILED
        }
    }

    /**
     * Connects to [peer] and sends the notice; returns `false` if it could not, or if no
     * session of the device answered within [ANSWER_TIMEOUT_MS]. The [room] keeps direct
     * connections off, see [KeyguardRoomSpec.allowLocalNetwork]: WebRTC goes through TURN,
     * so the removed device learns no network address of this one.
     */
    private suspend fun tell(
        room: KeyguardRoom,
        peer: RosterDevice,
        initiator: Boolean,
    ): Boolean = runCatchingUnlessCancelled {
        val role = if (initiator) ConnectRole.INITIATOR else ConnectRole.RESPONDER
        val selected = room.connect(peer.id, role)
        val stream = handshake(selected.stream, peer, initiator)
        try {
            notice(peer, roster()).forEach { frame ->
                stream.awaitWritable()
                stream.write(FrameCodec.encode(frame))
            }
            // The first frame of its session, its HELLO; it is not read further.
            val answered = withTimeoutOrNull(ANSWER_TIMEOUT_MS) { stream.read() } != null
            if (answered) delay(KeyguardPeerSession.BYE_LINGER_MS)
            answered
        } finally {
            withContext(NonCancellable) { stream.close() }
        }
    }.getOrDefault(false)

    /**
     * Returns the frames that tell [peer] that it was removed: HELLO, the [roster] and BYE,
     * as a session with a removed device starts. No item goes out: the envelope holds none.
     */
    private fun notice(peer: RosterDevice, roster: Roster): List<Frame> {
        val machine = SyncSessionStateMachine(
            config = SyncSessionConfig(
                localDeviceId = self,
                peerDeviceId = peer.id,
                peerNoisePub = peer.noisePub,
                groupId = membership.groupId,
                timings = dependencies.timings,
            ),
            roster = RosterValidator(dependencies.crypto, membership.groupId).validate(roster),
            crypto = dependencies.crypto,
            compressor = dependencies.compressor,
        )
        val local = LocalEnvelope(roster, Envelope(entries = emptyList()), dependencies.crypto::sha256)
        return machine.start(clock(), local)
            .filterIsInstance<SessionEffect.SendFrame>()
            .map { it.frame }
    }
}

/**
 * Removed peers of a validated roster that are still within the notification window. A
 * removal stamped further ahead than clocks drift is told to no one: its window would keep
 * the room of the peer joined for as long.
 */
internal fun Roster.revokedPeersToNotify(
    self: String,
    crypto: P2pCrypto,
    nowMillis: Long,
): List<RosterDevice> {
    val activity = RosterActivity(this, crypto::sha256)
    return devices.filter { device ->
        val age = activity.revokedAt(device.id)?.let { nowMillis - it.wall }
        device.id != self &&
            age != null &&
            age >= -HlcClock.MAX_FUTURE_DRIFT_MS &&
            age < KeyguardRevokedPeers.TELL_WINDOW_MS
    }
}
