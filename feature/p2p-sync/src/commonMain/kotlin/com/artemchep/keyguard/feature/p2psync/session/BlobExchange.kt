package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.readBigEndian
import com.artemchep.keyguard.feature.p2psync.writeBigEndian
import kotlinx.serialization.Serializable

/** The limits of a [BlobExchange]. */
data class BlobExchangeConfig(
    /** The most bytes one BLOB_DATA message carries. */
    val windowSize: Int = WINDOW_SIZE,
    /** The largest blob accepted. */
    val maxBlobSize: Long = MAX_BLOB_SIZE,
    /** How long a transfer may make no progress: no frame of its window arrives. */
    val timeoutMs: Long = TIMEOUT_MS,
    /** How long one window may take, progress or not. */
    val windowTimeoutMs: Long = WINDOW_TIMEOUT_MS,
) {
    init {
        require(maxBlobSize in 0L..Int.MAX_VALUE.toLong()) { "A blob must fit in a byte array" }
    }

    companion object {
        /**
         * 1 MiB less 1 KiB for the BLOB_DATA header and the padding header, so a full window
         * pads to exactly 1 MiB, a [MessagePadding] bucket.
         */
        const val WINDOW_SIZE: Int = 1024 * 1024 - 1024

        /** The largest BLOB_DATA message: a full window and its header pad to 1 MiB. */
        const val MAX_DATA_SIZE: Int = 1024 * 1024 - MessagePadding.HEADER_SIZE

        /** 100 MiB, the size limit of an attachment before it is encrypted. */
        const val MAX_ATTACHMENT_SIZE: Long = 100L * 1024L * 1024L

        /**
         * 1 MiB. A blob is an encrypted attachment, a few dozen bytes larger than the file
         * (the IV, the MAC and the padding), so a file at [MAX_ATTACHMENT_SIZE] still fits.
         */
        const val ENCRYPTION_MARGIN: Long = 1024L * 1024L

        /** The largest blob: an attachment at the size limit, encrypted. */
        const val MAX_BLOB_SIZE: Long = MAX_ATTACHMENT_SIZE + ENCRYPTION_MARGIN

        /** 30 seconds. */
        const val TIMEOUT_MS: Long = 30_000L

        /** 2 minutes: a window at 9 KiB a second, slower than any working connection. */
        const val WINDOW_TIMEOUT_MS: Long = 120_000L
    }
}

/**
 * Moves content-addressed blobs over a session, such as the words of a wordlist. A blob id
 * is the lowercase base32 SHA-256 of its bytes, see [hashOf].
 *
 * The puller asks for one blob at a time, one window at a time (BLOB_WANT with an offset),
 * so a transfer never floods the stream. The holder answers each want with one BLOB_DATA
 * window or with BLOB_NOPE. A received blob is published only once its SHA-256 matches its
 * id. The windows of a blob go into one buffer, sized by the first window.
 */
class BlobExchange(
    private val crypto: P2pCrypto,
    private val outgoing: OutgoingMessages = OutgoingMessages(),
    private val config: BlobExchangeConfig = BlobExchangeConfig(),
) {
    private val puller = BlobPuller(crypto, config, ::send)

    /**
     * Starts pulling the blob [hash], unless a transfer is running: the driver asks for one
     * blob at a time, once the last one ended.
     */
    fun want(hash: String): List<SessionEffect> = puller.start(hash)

    /**
     * Tells the peer which blobs this device holds: the first [MAX_ADVERTISED] by id, so the
     * message fits in [MAX_HAVE_SIZE].
     */
    fun advertise(hashes: Collection<String>): List<SessionEffect> {
        val advertised = hashes.sorted().take(MAX_ADVERTISED)
        return send(FrameType.BLOB_HAVE, BlobHavePayload.serializer().encodePayload(BlobHavePayload(advertised)))
    }

    /** Answers a [SessionEffect.ReadBlob]: [bytes] of the blob [hash] from [offset], of [totalSize]. */
    fun onBlobRead(hash: String, offset: Long, totalSize: Long, bytes: ByteArray): List<SessionEffect> =
        send(FrameType.BLOB_DATA, BlobData(hash, offset, totalSize, bytes).encode())

    /** Answers a [SessionEffect.ReadBlob] for a blob that is not here. */
    fun onBlobMissing(hash: String): List<SessionEffect> =
        send(FrameType.BLOB_NOPE, BlobRefPayload.serializer().encodePayload(BlobRefPayload(hash)))

    /** Handles a blob message of the peer. */
    fun onMessage(type: FrameType, payload: ByteArray): List<SessionEffect> = when (type) {
        FrameType.BLOB_HAVE -> BlobHavePayload.serializer().decodePayloadOrNull(payload)
            ?.let { listOf(SessionEffect.PeerBlobs(it.hashes)) }
            .orEmpty()

        FrameType.BLOB_WANT -> BlobWantPayload.serializer().decodePayloadOrNull(payload)
            ?.let { want ->
                // No window starts before the blob.
                if (want.off >= 0) {
                    listOf(SessionEffect.ReadBlob(want.h, want.off, config.windowSize))
                } else {
                    onBlobMissing(want.h)
                }
            }
            .orEmpty()

        FrameType.BLOB_DATA -> BlobData.decodeOrNull(payload)?.let(puller::onData).orEmpty()
        FrameType.BLOB_NOPE -> BlobRefPayload.serializer().decodePayloadOrNull(payload)
            ?.let { puller.onNope(it.h) }
            .orEmpty()

        else -> emptyList()
    }

    /** Fails a transfer that made no progress for too long. */
    fun onTick(now: Long): List<SessionEffect> = puller.onTick(now)

    /** A frame of a BLOB_DATA message arrived: the transfer moves on, even before its window is whole. */
    internal fun onDataFrame() {
        puller.onProgress()
    }

    private fun send(type: FrameType, payload: ByteArray): List<SessionEffect> =
        outgoing.frames(type, payload).map { SessionEffect.SendFrame(it) }

    companion object {
        /** The most blobs one BLOB_HAVE names. */
        const val MAX_ADVERTISED: Int = 4096

        /** 256 KiB, the largest BLOB_HAVE message: [MAX_ADVERTISED] ids of 52 characters fit. */
        const val MAX_HAVE_SIZE: Int = 256 * 1024

        private const val BLOB_ID_LENGTH = 52
        private const val SHA256_SIZE = 32

        /** Returns the blob id of [bytes]: the lowercase base32 SHA-256. */
        fun hashOf(crypto: P2pCrypto, bytes: ByteArray): String = hashOf(crypto::sha256, bytes)

        /** Returns the blob id of [bytes], digested with the SHA-256 [hasher], see [hashOf]. */
        fun hashOf(hasher: (ByteArray) -> ByteArray, bytes: ByteArray): String = P2pEncoding.base32(hasher(bytes))

        /** `true` if [text] is a blob id: the lowercase base32 of a SHA-256 digest, see [hashOf]. */
        fun isBlobId(text: String): Boolean = text.length == BLOB_ID_LENGTH &&
            P2pEncoding.base32OrNull(text)?.let { it.size == SHA256_SIZE && P2pEncoding.base32(it) == text } == true
    }
}

/** Downloads one blob at a time. */
internal class BlobPuller(
    private val crypto: P2pCrypto,
    private val config: BlobExchangeConfig,
    private val send: (FrameType, ByteArray) -> List<SessionEffect>,
) {
    private class Transfer(val hash: String) {
        /** The bytes of the blob; the first window allocates it, as large as the blob. */
        var buffer: ByteArray? = null
        var received: Long = 0L
    }

    private var current: Transfer? = null

    /** The time of the last tick, or `null` before the first one. */
    private var now: Long? = null

    /** When the current transfer last made progress, or `null` if that was before the first tick. */
    private var lastProgressAt: Long? = null

    /** When the current window was asked for, or `null` if that was before the first tick. */
    private var windowAskedAt: Long? = null

    fun start(hash: String): List<SessionEffect> {
        if (current != null) return emptyList()
        val transfer = Transfer(hash)
        current = transfer
        lastProgressAt = now
        return want(transfer)
    }

    fun onData(data: BlobData): List<SessionEffect> {
        val transfer = current?.takeIf { it.hash == data.hash && it.received == data.offset }
        val buffer = transfer?.buffer
        val end = data.offset + data.bytes.size
        return when {
            // A window of an earlier request; ignore it.
            transfer == null -> emptyList()
            data.total > config.maxBlobSize -> fail(transfer, BlobFailure.TOO_LARGE)
            // Every window must name the same size, the one the buffer has.
            buffer != null && buffer.size.toLong() != data.total -> fail(transfer, BlobFailure.PROTOCOL_ERROR)
            end > data.total || (data.bytes.isEmpty() && end < data.total) -> fail(transfer, BlobFailure.PROTOCOL_ERROR)
            else -> {
                val target = buffer ?: ByteArray(data.total.toInt()).also { transfer.buffer = it }
                data.bytes.copyInto(target, destinationOffset = data.offset.toInt())
                transfer.received = end
                lastProgressAt = now
                if (end == data.total) complete(transfer, target) else want(transfer)
            }
        }
    }

    /** A frame of the current window arrived. */
    fun onProgress() {
        if (current != null) lastProgressAt = now
    }

    fun onNope(hash: String): List<SessionEffect> {
        val transfer = current?.takeIf { it.hash == hash }
        return if (transfer == null) {
            emptyList()
        } else {
            current = null
            listOf(SessionEffect.BlobUnavailable(hash))
        }
    }

    fun onTick(now: Long): List<SessionEffect> {
        this.now = now
        val transfer = current ?: return emptyList()
        // Progress made before the first tick counts from that tick.
        val since = lastProgressAt ?: now.also { lastProgressAt = it }
        val asked = windowAskedAt ?: now.also { windowAskedAt = it }
        // A holder that trickles frames, or sends ones no window needs, still loses the blob.
        val late = now - since >= config.timeoutMs || now - asked >= config.windowTimeoutMs
        return if (late) fail(transfer, BlobFailure.TIMEOUT) else emptyList()
    }

    private fun want(transfer: Transfer): List<SessionEffect> {
        windowAskedAt = now
        val payload = BlobWantPayload(h = transfer.hash, off = transfer.received)
        return send(FrameType.BLOB_WANT, BlobWantPayload.serializer().encodePayload(payload))
    }

    private fun complete(transfer: Transfer, bytes: ByteArray): List<SessionEffect> {
        val verified = BlobExchange.hashOf(crypto, bytes) == transfer.hash
        current = null
        val result = if (verified) {
            SessionEffect.PublishBlob(transfer.hash, bytes)
        } else {
            SessionEffect.BlobFailed(transfer.hash, BlobFailure.HASH_MISMATCH)
        }
        return listOf(result)
    }

    private fun fail(transfer: Transfer, reason: BlobFailure): List<SessionEffect> {
        current = null
        return listOf(SessionEffect.BlobFailed(transfer.hash, reason))
    }
}

@Serializable
internal data class BlobHavePayload(val hashes: List<String>)

@Serializable
internal data class BlobWantPayload(val h: String, val off: Long)

@Serializable
internal data class BlobRefPayload(val h: String)

/** A BLOB_DATA payload: `u16 hashLength | hash | u64 offset | u64 total | bytes`. */
internal class BlobData(
    val hash: String,
    val offset: Long,
    val total: Long,
    val bytes: ByteArray,
) {
    fun encode(): ByteArray {
        val hashBytes = hash.encodeToByteArray()
        val header = ByteArray(HASH_LENGTH_SIZE + hashBytes.size + 2 * Long.SIZE_BYTES)
        header.writeBigEndian(0, HASH_LENGTH_SIZE, hashBytes.size.toLong())
        hashBytes.copyInto(header, destinationOffset = HASH_LENGTH_SIZE)
        header.writeBigEndian(HASH_LENGTH_SIZE + hashBytes.size, Long.SIZE_BYTES, offset)
        header.writeBigEndian(HASH_LENGTH_SIZE + hashBytes.size + Long.SIZE_BYTES, Long.SIZE_BYTES, total)
        return header + bytes
    }

    companion object {
        private const val HASH_LENGTH_SIZE = 2

        fun decodeOrNull(payload: ByteArray): BlobData? {
            val hashLength = if (payload.size >= HASH_LENGTH_SIZE) {
                payload.readBigEndian(0, HASH_LENGTH_SIZE).toInt()
            } else {
                -1
            }
            val headerSize = HASH_LENGTH_SIZE + hashLength + 2 * Long.SIZE_BYTES
            return if (hashLength < 0 || payload.size < headerSize) {
                null
            } else {
                val offset = payload.readBigEndian(HASH_LENGTH_SIZE + hashLength, Long.SIZE_BYTES)
                val total = payload.readBigEndian(HASH_LENGTH_SIZE + hashLength + Long.SIZE_BYTES, Long.SIZE_BYTES)
                BlobData(
                    hash = payload.copyOfRange(HASH_LENGTH_SIZE, HASH_LENGTH_SIZE + hashLength).decodeToString(),
                    offset = offset,
                    total = total,
                    bytes = payload.copyOfRange(headerSize, payload.size),
                ).takeIf { offset >= 0 && total >= 0 }
            }
        }
    }
}
