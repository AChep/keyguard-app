package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.Roster

/** The protocol versions of the sync session. */
object ProtocolVersion {
    /** The version this build speaks. */
    const val CURRENT: Int = 1

    /** The oldest version this build still accepts. */
    const val MIN_SUPPORTED: Int = 1
}

/**
 * Why a session ended, as this device sees it. A BYE carries the reason as its sender sees
 * it; the receiver mirrors it, see [SyncSessionStateMachine].
 */
enum class CloseReason {
    /** The other device speaks a protocol older than this build accepts. */
    PROTOCOL_TOO_OLD,

    /** The other device needs a newer protocol than this build speaks: update the app. */
    PROTOCOL_TOO_NEW,

    /** The other device belongs to another sync group. */
    GROUP_MISMATCH,

    /** The other device is not the expected one, or not in the roster. */
    UNKNOWN_PEER,

    /** The other device's key does not match its roster record. */
    IDENTITY_MISMATCH,

    /** The other device was removed from the group. */
    PEER_REVOKED,

    /** This device was removed from the group. */
    LOCAL_REVOKED,

    /** The other device stopped responding. */
    TIMEOUT,

    /** The other device ended the session. */
    PEER_CLOSED,

    /** The other device sent something this build can not read. */
    PROTOCOL_ERROR,

    /** This device ended the session. */
    LOCAL_CLOSED,

    /**
     * A device ended the session to connect again at once, over a new connection, such as
     * before the connection it runs over expires. Both devices see it so, and connect again
     * without a pause.
     */
    RECONNECT,
}

/**
 * Returns the reason a BYE gave, this one as its sender sees it, as its receiver sees it:
 * a reason about one side is mirrored. A BYE alone never says that a device was removed.
 * The roster that shows it comes first, and the receiver then ends the session itself;
 * without that roster, the sender merely closed.
 */
internal fun CloseReason?.seenByReceiver(): CloseReason = when (this) {
    CloseReason.PROTOCOL_TOO_OLD -> CloseReason.PROTOCOL_TOO_NEW
    CloseReason.PROTOCOL_TOO_NEW -> CloseReason.PROTOCOL_TOO_OLD
    CloseReason.GROUP_MISMATCH,
    CloseReason.UNKNOWN_PEER,
    CloseReason.IDENTITY_MISMATCH,
    CloseReason.PROTOCOL_ERROR,
    CloseReason.RECONNECT,
    -> this

    CloseReason.PEER_REVOKED,
    CloseReason.LOCAL_REVOKED,
    CloseReason.TIMEOUT,
    CloseReason.PEER_CLOSED,
    CloseReason.LOCAL_CLOSED,
    null,
    -> CloseReason.PEER_CLOSED
}

/** Why a blob transfer failed. */
enum class BlobFailure {
    /** The received bytes do not hash to the blob id. */
    HASH_MISMATCH,

    /** The blob is larger than allowed. */
    TOO_LARGE,

    /** The peer sent windows that do not fit the blob. */
    PROTOCOL_ERROR,

    /** The peer stopped sending. */
    TIMEOUT,
}

/** What a [SyncSessionStateMachine] or [BlobExchange] asks its driver to do. */
sealed interface SessionEffect {
    /** Write [frame] to the stream. */
    class SendFrame(val frame: Frame) : SessionEffect {
        /** The encoded frame. */
        val bytes: ByteArray get() = FrameCodec.encode(frame)

        override fun toString(): String = "SendFrame($frame)"
    }

    /**
     * Merge the [envelope] the peer sent into the local state, its entries checked against
     * the local roster with every roster of the session merged in: the [ApplyRoster] of the
     * roster that came with it, if it changed the roster of the session, comes first.
     * [hash] is the content hash of the envelope as sent, with the entries that could not be
     * decoded. Ask for it again with [SyncSessionStateMachine.requestPeerResend] if the
     * merge fails.
     */
    class ApplyEnvelope(
        val envelope: Envelope,
        val hash: String,
    ) : SessionEffect

    /**
     * Merge the [roster] of the session, which adopted the one the peer sent, into the local
     * one. Comes only when the roster of the session changed, or when the peer sent it again
     * after [SyncSessionStateMachine.requestPeerResend].
     */
    class ApplyRoster(val roster: Roster) : SessionEffect

    /**
     * The peer asked for the local roster and envelope again: supply the current ones with
     * [SyncSessionStateMachine.onLocalEnvelopeChanged]; they are sent even if unchanged.
     */
    data object RequestResend : SessionEffect

    /** The session is over; close the stream. [remote] is `true` if the peer ended it. */
    data class Close(val reason: CloseReason, val remote: Boolean = false) : SessionEffect

    /**
     * Read up to [maxLength] bytes of the local blob [hash] from [offset], then call
     * [BlobExchange.onBlobRead], or [BlobExchange.onBlobMissing] if the blob is not here.
     */
    data class ReadBlob(val hash: String, val offset: Long, val maxLength: Int) : SessionEffect

    /** The blob [hash] arrived and its SHA-256 matches; store it as verified. */
    class PublishBlob(val hash: String, val bytes: ByteArray) : SessionEffect

    /** The peer does not hold the blob [hash]. */
    data class BlobUnavailable(val hash: String) : SessionEffect

    /** The transfer of the blob [hash] failed. */
    data class BlobFailed(val hash: String, val reason: BlobFailure) : SessionEffect

    /** The peer holds the blobs [hashes]. */
    data class PeerBlobs(val hashes: List<String>) : SessionEffect
}
