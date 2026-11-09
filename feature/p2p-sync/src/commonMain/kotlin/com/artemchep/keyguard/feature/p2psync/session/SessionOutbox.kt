package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.transport.Compressor

/** Builds the outgoing frames of a sync session and decides what is worth sending. */
internal class SessionOutbox(
    private val state: SessionState,
    private val outgoing: OutgoingMessages,
    private val compressor: Compressor,
) {
    fun send(type: FrameType, payload: ByteArray): List<SessionEffect> =
        outgoing.frames(type, payload).map { SessionEffect.SendFrame(it) }

    fun hello(): List<SessionEffect> {
        val payload = HelloPayload(
            v = ProtocolVersion.CURRENT,
            min = ProtocolVersion.MIN_SUPPORTED,
            device = state.config.localDeviceId,
            group = state.config.groupId,
            roster = state.rosterHash,
            envelope = state.localHash,
        )
        return send(FrameType.HELLO, HelloPayload.serializer().encodePayload(payload))
    }

    /**
     * Sends the roster if the peer may not have it yet: it goes before the envelope, so the
     * peer knows every writer of the entries. A roster larger than a ROSTER message may be
     * is not sent; it never is, as [Roster.MAX_DEVICES] records fit.
     */
    fun rosterIfNeeded(): List<SessionEffect> {
        val hash = state.rosterHash
        val known = hash == state.peerRosterHash || hash == state.lastSentRosterHash
        if (known) return emptyList()
        state.lastSentRosterHash = hash
        val payload = Roster.serializer().encodePayload(state.roster)
        return if (payload.size <= FrameType.ROSTER.maxSize) send(FrameType.ROSTER, payload) else emptyList()
    }

    /**
     * Sends the local envelope unless the peer acknowledged it, already has the same content
     * or it is already on its way; a forced send always goes out. Of an envelope too large
     * to send, the peer is told once instead.
     */
    fun envelopeIfNeeded(): List<SessionEffect> {
        val envelope = state.localEnvelope
        val hash = state.localHash
        val known = hash == state.peerAckedHash || hash == state.peerContentHash || hash == state.lastSentHash
        val tooLarge = state.localTooLargeSize != null
        val skip = (known && !state.forceSend) || tooLarge
        return if (envelope == null || hash == null || skip) {
            emptyList()
        } else {
            state.forceSend = false
            sendEnvelope(envelope, hash)
        }
    }

    private fun sendEnvelope(envelope: LocalEnvelope, hash: String): List<SessionEffect> {
        // The peer parses no envelope past the limits; the message must fit, even if
        // compression made it larger.
        val payload = compressor
            .takeIf { envelope.fits(state.config.maxEnvelopeSize) }
            ?.let(envelope::compressed)
            ?.takeIf { it.size <= FrameType.ENVELOPE.maxSize }
        return if (payload == null) {
            state.localTooLarge = TooLargeEnvelope(hash, envelope.bytes.size)
            // Tell the peer, so it does not wait for an envelope that never comes.
            sendHash(FrameType.ENVELOPE_TOO_LARGE, hash)
        } else {
            state.lastSentHash = hash
            send(FrameType.ENVELOPE, payload)
        }
    }

    /** Sends an empty HEARTBEAT: any frame shows the peer that this device is there. */
    fun heartbeat(): List<SessionEffect> {
        state.lastHeartbeatAt = state.now
        return send(FrameType.HEARTBEAT, ByteArray(0))
    }

    fun ack(hash: String): List<SessionEffect> = sendHash(FrameType.ENVELOPE_ACK, hash)

    /** Asks the peer for its roster and envelope again, within the budget of the session. */
    fun resendRequest(): List<SessionEffect> {
        val allowed = state.resendRequestsSent < state.config.timings.maxResendRequests
        return if (allowed) {
            state.resendRequestsSent++
            state.rosterResent = true
            send(FrameType.RESEND_REQUEST, ByteArray(0))
        } else {
            emptyList()
        }
    }

    private fun sendHash(type: FrameType, hash: String): List<SessionEffect> =
        send(type, HashPayload.serializer().encodePayload(HashPayload(hash)))

    /** Ends the session, telling the peer why if [notifyPeer]. */
    fun close(reason: CloseReason, notifyPeer: Boolean = true): List<SessionEffect> {
        val bye = if (notifyPeer && state.isOpen) {
            send(FrameType.BYE, ByePayload.serializer().encodePayload(ByePayload(reason.name)))
        } else {
            emptyList()
        }
        state.phase = SessionPhase.CLOSED
        return bye + SessionEffect.Close(reason)
    }
}
