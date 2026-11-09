package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.transport.Compressor

/**
 * One sync session with a peer, over an already authenticated (Noise KKhfs) stream, which is
 * reliable and ordered: a frame that does not fit the framing ends the session. Sans-IO:
 * the driver feeds it received bytes, clock ticks and local changes, and carries out the
 * returned [SessionEffect]s in order.
 *
 * - Both sides start with HELLO: protocol version and the oldest one accepted,
 *   capabilities, and the hashes of their roster and envelope. The peer must be the
 *   expected device, with the static key of its roster record, and active. Nothing the
 *   peer sends before its HELLO counts.
 * - The local roster is sent in a ROSTER message whenever the peer may not have its hash,
 *   and always before the envelope; the receiver adopts it before it applies the entries.
 *   A receiver that can not merge the peer's roster applies no envelope until it merges one.
 * - The local envelope is sent whenever its hash changes, unless the peer acknowledged
 *   that hash or already has the same content. Every received envelope is acknowledged.
 *   The sender of an envelope is the peer of the session.
 * - An envelope larger than [SyncSessionConfig.maxEnvelopeSize] is never sent; the session
 *   tells the peer, shows it in [localEnvelopeTooLarge] and goes on. One that arrives is
 *   dropped, and not asked for again, see [isPeerEnvelopeTooLarge].
 * - An empty heartbeat goes out every 10 seconds; a peer silent for 30 seconds is dead.
 * - The host asks the peer to send its roster and envelope again with [requestPeerResend]:
 *   when it could not apply them, or can take more of them now. A session sends and
 *   answers at most 3 such requests.
 * - A changed roster is sent at once. When the peer is revoked the session tells it and
 *   ends; when this device is revoked it tells the peer and ends. Even a session that ends
 *   so at its start says HELLO first, so the peer reads the roster that tells it why.
 * - The BYE of the peer gives its reason as the peer sees it; the session reports it as
 *   this device sees it. A BYE alone never says that a device was removed: only a roster
 *   that shows it does.
 *
 * Not thread-safe: the driver serializes calls.
 *
 * @param roster the validated local roster.
 */
class SyncSessionStateMachine(
    config: SyncSessionConfig,
    roster: Roster,
    crypto: P2pCrypto,
    compressor: Compressor,
) {
    private val state = SessionState(config, roster)
    private val outgoing = OutgoingMessages()
    private val outbox = SessionOutbox(state, outgoing, compressor)

    /** The blob transfers of this session; its effects are [SessionEffect]s too. */
    val blobs: BlobExchange = BlobExchange(crypto, outgoing, config.blobs)

    private val inbox = SessionInbox(state, outbox, blobs, crypto, compressor)

    /** `true` once the session ended. */
    val isClosed: Boolean get() = state.phase == SessionPhase.CLOSED

    /** `true` once both sides accepted each other's HELLO. */
    val isEstablished: Boolean get() = state.phase == SessionPhase.ESTABLISHED

    /** The content hash of the local envelope the session holds. */
    val localHash: String? get() = state.localHash

    /** The current merged roster, by hash, independently of the data envelope. */
    val rosterHash: String get() = state.rosterHash

    /** The roster the peer announced or sent, by hash; not a durable-storage receipt. */
    val peerRosterHash: String? get() = state.peerRosterHash

    /**
     * `true` while the session is established and the peer acknowledged, or already had,
     * the local envelope.
     */
    val isPeerUpToDate: Boolean
        get() {
            val hash = state.localHash
            return isEstablished && hash != null && (hash == state.peerAckedHash || hash == state.peerContentHash)
        }

    /**
     * `true` while a roster or envelope of the peer is partly received. Heartbeats name no
     * state, so only this shows that the peer's state is on its way before it arrives.
     */
    val isReceiving: Boolean get() = inbox.isReceiving

    /**
     * `true` while the session is established and both sides hold each other's latest
     * envelope: the peer is up to date, its latest envelope arrived or equals the local one,
     * and nothing of the peer is still arriving, see [isReceiving].
     */
    val isSettled: Boolean get() = isPeerUpToDate && hasPeerEnvelope && !isReceiving

    /**
     * The size of the local envelope, as canonical JSON bytes, if it is larger than
     * [SyncSessionConfig.maxEnvelopeSize] and so is not sent; else `null`.
     */
    val localEnvelopeTooLarge: Int? get() = state.localTooLargeSize

    /**
     * `true` if the peer's latest envelope is too large: it sent one larger than
     * [SyncSessionConfig.maxEnvelopeSize], or said it can not send it.
     */
    val isPeerEnvelopeTooLarge: Boolean get() = state.peerEnvelopeTooLarge

    /**
     * `true` while the session is established and nothing more can cross: it is [isSettled],
     * or an envelope too large to send or to take stands in the way. A roster or envelope
     * that is still arriving can cross.
     */
    val isExchangeDone: Boolean
        get() {
            val sent = isPeerUpToDate || (isEstablished && localEnvelopeTooLarge != null)
            val received = hasPeerEnvelope || state.peerEnvelopeTooLarge
            return sent && received && !isReceiving
        }

    /** `true` if the peer's latest envelope arrived or equals the local one. */
    private val hasPeerEnvelope: Boolean
        get() {
            val peer = state.peerContentHash
            return peer == null || peer == state.lastReceivedHash || peer == state.localHash
        }

    /**
     * Starts the session at [now] with the current local [envelope]: sends HELLO, then ends
     * the session at once if either device is revoked.
     */
    fun start(now: Long, envelope: LocalEnvelope): List<SessionEffect> {
        check(state.phase == SessionPhase.NEW) { "The session was already started" }
        state.now = now
        state.lastHeardAt = now
        state.lastHeartbeatAt = now
        state.phase = SessionPhase.AWAITING_HELLO
        setLocalEnvelope(envelope)
        // The peer reads nothing before HELLO, so it goes first even to a revoked peer.
        return outbox.hello() + inbox.revocationCheck()
    }

    /** Handles one frame received from the peer. */
    fun onFrame(bytes: ByteArray): List<SessionEffect> {
        val frame = FrameCodec.decodeOrNull(bytes)
        if (state.isOpen) state.heardSinceTick = true
        return when {
            !state.isOpen -> emptyList()
            frame == null -> outbox.close(CloseReason.PROTOCOL_ERROR)
            else -> inbox.onFrame(frame)
        }
    }

    /** Advances the timers to [now]; call it every second or so. */
    fun onTick(now: Long): List<SessionEffect> {
        state.now = now
        if (state.heardSinceTick) {
            state.lastHeardAt = now
            state.heardSinceTick = false
        }
        val timings = state.config.timings
        return when {
            !state.isOpen -> emptyList()
            now - state.lastHeardAt >= timings.deadAfterMs -> outbox.close(CloseReason.TIMEOUT, notifyPeer = false)
            state.phase != SessionPhase.ESTABLISHED -> emptyList()
            else -> heartbeatIfDue(now) + blobs.onTick(now)
        }
    }

    /**
     * Hands over the current local roster and envelope after a local change or a merge, or
     * in answer to [SessionEffect.RequestResend]. Each is sent if the peer may not have it.
     */
    fun onLocalEnvelopeChanged(envelope: LocalEnvelope): List<SessionEffect> = if (state.isOpen) {
        setLocalEnvelope(envelope)
        val revoked = inbox.revocationCheck()
        when {
            !state.isOpen -> revoked
            state.phase == SessionPhase.ESTABLISHED -> outbox.rosterIfNeeded() + outbox.envelopeIfNeeded()
            else -> emptyList()
        }
    } else {
        emptyList()
    }

    /**
     * Asks the peer to send its roster and envelope again, within the budget of the
     * session, see [SyncSessionTimings.maxResendRequests]: the host could not apply them,
     * syncs more of them now, or a write it dropped as too far in the future is due. The
     * peer sends them even if this device acknowledged them.
     */
    fun requestPeerResend(): List<SessionEffect> =
        if (state.phase == SessionPhase.ESTABLISHED) outbox.resendRequest() else emptyList()

    /** Ends the session, telling the peer. */
    fun close(reason: CloseReason = CloseReason.LOCAL_CLOSED): List<SessionEffect> =
        if (state.isOpen) outbox.close(reason) else emptyList()

    private fun setLocalEnvelope(envelope: LocalEnvelope) {
        inbox.adoptRoster(envelope.roster)
        state.localEnvelope = envelope
    }

    private fun heartbeatIfDue(now: Long): List<SessionEffect> {
        val due = now - state.lastHeartbeatAt >= state.config.timings.heartbeatIntervalMs
        return if (due) outbox.heartbeat() else emptyList()
    }
}
