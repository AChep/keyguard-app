package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeDecoding
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.feature.p2psync.transport.Compressor
import com.artemchep.keyguard.feature.p2psync.transport.Decompressed

/** Handles the incoming messages of a sync session. */
internal class SessionInbox(
    private val state: SessionState,
    private val outbox: SessionOutbox,
    private val blobs: BlobExchange,
    private val crypto: P2pCrypto,
    private val compressor: Compressor,
) {
    private val reassembler = Reassembler()
    private val validator = RosterValidator(crypto, state.config.groupId)

    /**
     * `true` while a ROSTER or ENVELOPE message of the peer is partly received: the peer's
     * state is on its way, whatever the hashes say.
     */
    val isReceiving: Boolean
        get() = reassembler.hasPartial(FrameType.ENVELOPE) || reassembler.hasPartial(FrameType.ROSTER)

    fun onFrame(frame: Frame): List<SessionEffect> {
        val result = reassembler.accept(frame)
        // A blob window takes many frames: each one the reassembler takes shows that the
        // transfer moves on. A window still has to arrive whole in time, see BlobPuller.
        val accepted = result !is ReassemblyResult.Failed
        if (accepted && state.phase == SessionPhase.ESTABLISHED && frame.type == FrameType.BLOB_DATA) {
            blobs.onDataFrame()
        }
        return when (result) {
            ReassemblyResult.Pending -> emptyList()
            is ReassemblyResult.Complete -> onMessage(result.message)
            // The stream is reliable and ordered: a frame that does not fit is a broken peer.
            is ReassemblyResult.Failed -> outbox.close(CloseReason.PROTOCOL_ERROR)
        }
    }

    /**
     * Merges [incoming] into the roster of the session, dropping forged records. Returns
     * `false` if the merge was refused whole, see [RosterValidator.mergeOrNull].
     */
    fun adoptRoster(incoming: Roster): Boolean {
        // Merging is idempotent: the current roster itself changes nothing.
        val adopted = incoming == state.roster && state.rosterHash.isNotEmpty()
        if (adopted || incoming.founder != state.roster.founder) return true
        val merged = validator.mergeOrNull(state.roster, incoming)
        val roster = merged ?: validator.validate(state.roster)
        if (roster != state.roster || state.rosterHash.isEmpty()) {
            state.roster = roster
            state.rosterHash = roster.contentHash(crypto::sha256)
        }
        return merged != null
    }

    /** Ends the session if this device or the peer is no longer active. */
    fun revocationCheck(): List<SessionEffect> {
        val activity = RosterActivity(state.roster, crypto::sha256)
        val peer = state.config.peerDeviceId
        return when {
            !state.isOpen -> emptyList()
            // A device that removed itself tells the peer before it goes.
            !activity.isActive(state.config.localDeviceId) ->
                outbox.rosterIfNeeded() + outbox.close(CloseReason.LOCAL_REVOKED)

            // Tell the revoked peer why before cutting it off.
            !activity.isActive(peer) ->
                outbox.rosterIfNeeded() + outbox.close(CloseReason.PEER_REVOKED)

            else -> emptyList()
        }
    }

    private fun onMessage(message: Message): List<SessionEffect> {
        val type = message.type
        return when {
            type == FrameType.HELLO -> onHello(message.payload)
            type == FrameType.BYE -> onBye(message.payload)
            // Nothing but HELLO counts before the peer is verified; unknown types are skipped.
            state.phase != SessionPhase.ESTABLISHED || type == null -> emptyList()
            type.channel == FrameChannel.BLOB -> blobs.onMessage(type, message.payload)
            else -> onSessionMessage(type, message.payload)
        }
    }

    private fun onSessionMessage(type: FrameType, payload: ByteArray): List<SessionEffect> = when (type) {
        FrameType.ENVELOPE -> onEnvelope(payload)
        FrameType.ENVELOPE_ACK -> {
            HashPayload.serializer().decodePayloadOrNull(payload)?.let { state.peerAckedHash = it.h }
            emptyList()
        }

        // A peer may ask as often as this device would ask it; each answer is a whole envelope.
        FrameType.RESEND_REQUEST -> if (state.resendRequestsReceived < state.config.timings.maxResendRequests) {
            state.resendRequestsReceived++
            // The peer may have dropped the roster with the envelope.
            state.peerRosterHash = null
            state.lastSentRosterHash = null
            state.forceSend = true
            listOf(SessionEffect.RequestResend)
        } else {
            emptyList()
        }

        // Any frame keeps the session alive, see SyncSessionStateMachine.onFrame.
        FrameType.HEARTBEAT -> emptyList()
        FrameType.ROSTER -> onRoster(payload)
        FrameType.ENVELOPE_TOO_LARGE -> {
            HashPayload.serializer().decodePayloadOrNull(payload)?.let { state.peerContentHash = it.h }
            onPeerEnvelopeTooLarge()
        }

        else -> emptyList()
    }

    private fun onHello(payload: ByteArray): List<SessionEffect> {
        val hello = HelloPayload.serializer().decodePayloadOrNull(payload)
        val reason = hello?.let { verifyHello(state, it, crypto) }
        return when {
            state.phase != SessionPhase.AWAITING_HELLO -> emptyList()
            hello == null -> outbox.close(CloseReason.PROTOCOL_ERROR)
            reason != null -> outbox.close(reason)
            else -> {
                state.phase = SessionPhase.ESTABLISHED
                state.peerContentHash = hello.envelope
                state.peerRosterHash = hello.roster
                outbox.rosterIfNeeded() + outbox.envelopeIfNeeded()
            }
        }
    }

    /**
     * Takes the envelope of the peer. Its entries are the peer's: the roster that names
     * their writers came before them, in a ROSTER message, see [onRoster]. While the
     * session could not adopt the peer's roster, the envelope is acknowledged but not
     * applied.
     */
    private fun onEnvelope(payload: ByteArray): List<SessionEffect> {
        val decompressed = compressor.decompress(payload, state.config.maxEnvelopeSize)
        val decoding = (decompressed as? Decompressed.Bytes)
            ?.let { EnvelopeCodec.decode(it.bytes, crypto::sha256) }
            ?: EnvelopeDecoding.Malformed
        return when {
            decompressed == Decompressed.TooLarge || decoding == EnvelopeDecoding.TooLarge -> onPeerEnvelopeTooLarge()
            // Asking again brings the same envelope: one of the devices needs an update.
            decoding is EnvelopeDecoding.UnsupportedVersion -> outbox.close(
                if (decoding.version > Envelope.VERSION) CloseReason.PROTOCOL_TOO_NEW else CloseReason.PROTOCOL_TOO_OLD,
            )

            // The stream lost nothing: asking again brings the same bytes.
            decoding !is EnvelopeDecoding.Valid -> outbox.close(CloseReason.PROTOCOL_ERROR)
            else -> {
                // The hash of the envelope as sent, so the ACK names it even if entries were skipped.
                val hash = decoding.contentHash
                state.lastReceivedHash = hash
                state.peerContentHash = hash
                state.peerEnvelopeTooLarge = false
                // Its entries would be checked against a roster without the peer's revocations.
                val apply = SessionEffect.ApplyEnvelope(decoding.envelope, hash).takeUnless { state.peerRosterRefused }
                outbox.ack(hash) + listOfNotNull(apply)
            }
        }
    }

    /**
     * The peer's envelope is too large: it sent one, or said it can not. Asking again
     * brings the same one, so wait for the peer to send another.
     */
    private fun onPeerEnvelopeTooLarge(): List<SessionEffect> {
        state.peerEnvelopeTooLarge = true
        return emptyList()
    }

    /**
     * Adopts the roster of the peer before any envelope that follows it: the entries of
     * that envelope are checked against the roster of the session, see
     * [SessionEffect.ApplyEnvelope]. The host gets the roster of the session only if it
     * changed, or if the host asked for it again, see [SessionState.rosterResent].
     */
    private fun onRoster(payload: ByteArray): List<SessionEffect> {
        val roster = Roster.serializer().decodePayloadOrNull(payload)
        return if (roster == null) {
            outbox.close(CloseReason.PROTOCOL_ERROR)
        } else {
            state.peerRosterHash = roster.contentHash(crypto::sha256)
            val before = state.rosterHash
            state.peerRosterRefused = !adoptRoster(roster)
            // The host has the roster of the session already, unless it asked for it again.
            val apply = state.rosterHash != before || state.rosterResent
            state.rosterResent = false
            listOfNotNull(SessionEffect.ApplyRoster(state.roster).takeIf { apply }) + revocationCheck()
        }
    }

    private fun onBye(payload: ByteArray): List<SessionEffect> {
        val name = ByePayload.serializer().decodePayloadOrNull(payload)?.reason
        val sent = CloseReason.entries.firstOrNull { it.name == name }
        return if (state.isOpen) {
            val reason = sent.seenByReceiver()
            state.phase = SessionPhase.CLOSED
            listOf(SessionEffect.Close(reason, remote = true))
        } else {
            emptyList()
        }
    }
}

private fun verifyHello(state: SessionState, hello: HelloPayload, crypto: P2pCrypto): CloseReason? {
    val record = state.roster.devices.firstOrNull { it.id == hello.device }
    return when {
        hello.v < ProtocolVersion.MIN_SUPPORTED -> CloseReason.PROTOCOL_TOO_OLD
        hello.min > ProtocolVersion.CURRENT -> CloseReason.PROTOCOL_TOO_NEW
        hello.group != state.config.groupId -> CloseReason.GROUP_MISMATCH
        hello.device != state.config.peerDeviceId || record == null -> CloseReason.UNKNOWN_PEER
        record.noisePub != state.config.peerNoisePub -> CloseReason.IDENTITY_MISMATCH
        !RosterActivity(state.roster, crypto::sha256).isActive(hello.device) -> CloseReason.PEER_REVOKED
        else -> null
    }
}
