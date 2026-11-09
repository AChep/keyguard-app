package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentConfig

/** The scheduling class of a frame, see [Multiplexer]. */
enum class FrameChannel {
    /** Small protocol frames; always sent first. */
    CONTROL,

    /** Envelopes, rosters and enrollment bundles. */
    SYNC,

    /** Blobs, see [BlobExchange]. */
    BLOB,
}

/** 4 KiB: a message of a few ids and hashes. */
private const val SMALL_MESSAGE_SIZE: Int = 4 * 1024

/** 64 KiB: a message that holds a device record. */
private const val RECORD_MESSAGE_SIZE: Int = 64 * 1024

/** 64 KiB: a message of a type this build does not know, which it skips. */
private const val UNKNOWN_MESSAGE_SIZE: Int = 64 * 1024

/**
 * The frame types of the session and enrollment protocols.
 *
 * Each type takes messages up to its [maxSize], so a stream holds no more of a message than
 * its type needs. A sender never sends a larger one; a receiver refuses it, see [Reassembler].
 */
enum class FrameType(
    /** The type byte on the wire. */
    val code: Int,
    /** How the frame is scheduled. */
    val channel: FrameChannel,
    /** The largest message of the type, before padding. */
    val maxSize: Int = SMALL_MESSAGE_SIZE,
) {
    /** Protocol version, capabilities and state hashes; the first frame of a session. */
    HELLO(code = 0x01, channel = FrameChannel.CONTROL),

    /** A whole [com.artemchep.keyguard.feature.p2psync.model.Envelope]. */
    ENVELOPE(code = 0x02, channel = FrameChannel.SYNC, maxSize = SyncSessionConfig.MAX_ENVELOPE_SIZE),

    /** The receipt of an envelope, by its content hash. */
    ENVELOPE_ACK(code = 0x03, channel = FrameChannel.CONTROL),

    /** Asks the peer to send its roster and envelope again; carries nothing. */
    RESEND_REQUEST(code = 0x04, channel = FrameChannel.CONTROL),

    /** Keeps the session alive; carries nothing. */
    HEARTBEAT(code = 0x05, channel = FrameChannel.CONTROL),

    /**
     * The roster of the sender: sent before its envelope, and as soon as it changes, so
     * revocations spread at once.
     */
    ROSTER(code = 0x06, channel = FrameChannel.SYNC, maxSize = SyncSessionConfig.MAX_ENVELOPE_SIZE),

    /** The sender's envelope, by its content hash, is too large to send. */
    ENVELOPE_TOO_LARGE(code = 0x07, channel = FrameChannel.CONTROL),

    /** The blobs the sender holds. */
    BLOB_HAVE(code = 0x10, channel = FrameChannel.BLOB, maxSize = BlobExchange.MAX_HAVE_SIZE),

    /** Asks for a window of a blob. */
    BLOB_WANT(code = 0x11, channel = FrameChannel.BLOB),

    /** A window of a blob. */
    BLOB_DATA(code = 0x12, channel = FrameChannel.BLOB, maxSize = BlobExchangeConfig.MAX_DATA_SIZE),

    /** The sender does not hold the wanted blob. */
    BLOB_NOPE(code = 0x13, channel = FrameChannel.BLOB),

    /** Ends the session, with a reason. */
    BYE(code = 0x20, channel = FrameChannel.CONTROL),

    /** The device record and role of an enrolling device. */
    ENROLL_HELLO(code = 0x40, channel = FrameChannel.CONTROL, maxSize = RECORD_MESSAGE_SIZE),

    /** The [com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentBundle] the member sends to the joiner. */
    ENROLL_BUNDLE(code = 0x41, channel = FrameChannel.SYNC, maxSize = EnrollmentConfig.MAX_BUNDLE_SIZE),

    /** The joiner stored the bundle. */
    ENROLL_ACK(code = 0x42, channel = FrameChannel.CONTROL),

    /** A device ends the enrollment. */
    ENROLL_REJECT(code = 0x43, channel = FrameChannel.CONTROL),

    /** The displayer reveals the nonce its ENROLL_HELLO committed to. */
    ENROLL_REVEAL(code = 0x44, channel = FrameChannel.CONTROL),

    /** The joiner's user confirmed the digits; the member may send the bundle. */
    ENROLL_CONFIRM(code = 0x45, channel = FrameChannel.CONTROL),
    ;

    companion object {
        private val byCode: Map<Int, FrameType> = entries.associateBy { it.code }

        /** Returns the type with the wire [code], or `null` for a type this build does not know. */
        fun of(code: Int): FrameType? = byCode[code]

        /**
         * The largest message, before padding, of the type with the wire [code]: its
         * [maxSize], or 64 KiB for a type this build does not know. A newer build sends a
         * larger message of a new type only to a peer that knows the type.
         */
        fun maxSizeOf(code: Int): Int = of(code)?.maxSize ?: UNKNOWN_MESSAGE_SIZE
    }
}
