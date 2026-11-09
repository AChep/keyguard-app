package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster

/** The timers and budgets of a sync session. */
data class SyncSessionTimings(
    /** How often a heartbeat is sent. */
    val heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
    /** How long the peer may stay silent before the session is dead. */
    val deadAfterMs: Long = DEAD_AFTER_MS,
    /** How many RESEND_REQUEST frames one session may send, and answers. */
    val maxResendRequests: Int = MAX_RESEND_REQUESTS,
) {
    companion object {
        /** 10 seconds. */
        const val HEARTBEAT_INTERVAL_MS: Long = 10_000L

        /** 30 seconds. */
        const val DEAD_AFTER_MS: Long = 30_000L

        /** Resend requests per session. */
        const val MAX_RESEND_REQUESTS: Int = 3
    }
}

/**
 * Who a sync session is between.
 *
 * @property peerNoisePub the base64url Noise static key the peer authenticated with; it
 *   must match the peer's roster record.
 * @property blobs the limits of the blobs the session pulls.
 * @property maxEnvelopeSize the largest envelope, as uncompressed canonical JSON bytes, that
 *   the session sends or accepts.
 */
data class SyncSessionConfig(
    val localDeviceId: String,
    val peerDeviceId: String,
    val peerNoisePub: String,
    val groupId: String,
    val timings: SyncSessionTimings = SyncSessionTimings(),
    val blobs: BlobExchangeConfig = BlobExchangeConfig(),
    val maxEnvelopeSize: Int = MAX_ENVELOPE_SIZE,
) {
    companion object {
        /**
         * 2 MiB, a few thousand entries at about 700 bytes each. The parsed tree of an
         * envelope takes many times its bytes, see
         * [com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec.limits].
         */
        const val MAX_ENVELOPE_SIZE: Int = 2 * 1024 * 1024
    }
}

/** The phase of a sync session. */
internal enum class SessionPhase {
    NEW,
    AWAITING_HELLO,
    ESTABLISHED,
    CLOSED,
}

/** The mutable state of one sync session, shared by its parts. */
internal class SessionState(
    val config: SyncSessionConfig,
    var roster: Roster,
) {
    var phase: SessionPhase = SessionPhase.NEW
    var rosterHash: String = ""
    var localEnvelope: LocalEnvelope? = null
    val localHash: String? get() = localEnvelope?.contentHash

    /** The hash of the peer's content, from its HELLO or its last envelope. */
    var peerContentHash: String? = null
    var peerRosterHash: String? = null
    var peerAckedHash: String? = null
    var lastSentHash: String? = null
    var lastSentRosterHash: String? = null
    var lastReceivedHash: String? = null
    var forceSend: Boolean = false
    var resendRequestsSent: Int = 0
    var resendRequestsReceived: Int = 0

    /** The last local envelope found too large to send. */
    var localTooLarge: TooLargeEnvelope? = null

    /** `true` from an envelope of the peer that was too large to take until the next one. */
    var peerEnvelopeTooLarge: Boolean = false

    /** `true` from a roster of the peer the session could not adopt until one it adopts. */
    var peerRosterRefused: Boolean = false

    /**
     * `true` from a resend request of this device until the peer's roster arrives: the host
     * may have dropped the roster it got, so the session hands it over again.
     */
    var rosterResent: Boolean = false

    var now: Long = 0L
    var lastHeardAt: Long = 0L
    var heardSinceTick: Boolean = false
    var lastHeartbeatAt: Long = 0L

    val isOpen: Boolean get() = phase == SessionPhase.AWAITING_HELLO || phase == SessionPhase.ESTABLISHED

    /** The size of the current local envelope if it is too large to send, else `null`. */
    val localTooLargeSize: Int?
        get() = localTooLarge?.takeIf { it.hash == localHash }?.size
}

/** The local envelope [hash], [size] bytes of canonical JSON, too large to send. */
internal class TooLargeEnvelope(
    val hash: String,
    val size: Int,
)
