package com.artemchep.keyguard.feature.p2psync.transport

/** How long a Noise handshake over a [ByteStream] may take: 20 seconds. */
const val NOISE_HANDSHAKE_TIMEOUT_MS: Long = 20_000L

/**
 * A reliable, ordered, message-oriented stream between two devices, such as a WebRTC data
 * channel or a relay-forwarded room. Each write delivers one message.
 */
interface ByteStream {
    /** Returns the next message, or `null` once the stream is closed. */
    suspend fun read(): ByteArray?

    /** Sends one message. */
    suspend fun write(bytes: ByteArray)

    /** Suspends until the stream can take more data without buffering too much. */
    suspend fun awaitWritable()

    /** Closes the stream; pending reads return `null`. */
    suspend fun close()
}

/** How a [ByteStream] reaches the peer. */
enum class TransportKind {
    /** A WebRTC data channel: on the LAN, through STUN or through TURN. */
    WEBRTC,

    /** Frames forwarded by the relay server. */
    RELAY,
}

/** Which side opens a connection. */
enum class ConnectRole {
    /** Dials the peer. */
    INITIATOR,

    /** Waits for the peer. */
    RESPONDER,
}

/** Opens [ByteStream]s of one [kind] to peers of the sync group. */
interface Transport {
    /** How this transport reaches peers. */
    val kind: TransportKind

    /** Connects to the device [peerId], or throws if it can not be reached. */
    suspend fun connect(peerId: String, role: ConnectRole): ByteStream
}

/** Compresses envelopes; gzip in the application. Enrollment bundles go uncompressed. */
interface Compressor {
    /** Compresses [bytes]. */
    fun compress(bytes: ByteArray): ByteArray

    /** Decompresses [bytes] into at most [maxSize] bytes. */
    fun decompress(bytes: ByteArray, maxSize: Int): Decompressed
}

/** What [Compressor.decompress] made of its input. */
sealed interface Decompressed {
    class Bytes(val bytes: ByteArray) : Decompressed

    /** The input inflates past the size limit. */
    data object TooLarge : Decompressed

    /** The input is not compressed data. */
    data object Invalid : Decompressed
}
