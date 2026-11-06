package com.artemchep.keyguard.nativecrypto

private val EMPTY = ByteArray(0)

/**
 * The Noise handshake patterns of device-to-device sync. Both use Curve25519,
 * ChaCha20-Poly1305 and SHA-256.
 */
public enum class NativeNoisePattern(
    internal val proto: NoisePatternProto,
) {
    /**
     * `Noise_XXpsk3_25519_ChaChaPoly_SHA256`, for pairing: each side learns the
     * other's static key, and a pre-shared key authenticates the exchange.
     *
     * The pre-shared key enters only with the third message, which the
     * initiator writes:
     * - The first two messages are not authenticated by it, so send them
     *   without a payload.
     * - The initiator finishes before the responder has proven it knows the
     *   key. Until the initiator has read one transport message, or matched
     *   [NativeNoiseHandshake.remoteStaticKey] against a key it got out of band,
     *   that remote key may be an attacker's.
     *
     * Use [NativeCryptoNoise.PSK_SIZE] random bytes as the pre-shared key: an
     * attacker that answers as the responder can test guesses of it offline.
     */
    XX_PSK3(NoisePatternProto.XX_PSK3),

    /**
     * `Noise_KK_25519_ChaChaPoly_SHA256`, for sessions between paired devices:
     * each side already knows the other's static key. The first message's
     * payload can be replayed, and leaks if the responder's static key does, so
     * send it without one.
     */
    KK(NoisePatternProto.KK),
}

/** A Curve25519 static key pair. The arrays are used as given, not copied. */
public class NativeNoiseKeyPair(
    public val privateKey: ByteArray,
    public val publicKey: ByteArray,
) {
    init {
        require(privateKey.size == KEY_SIZE) { "A private key is $KEY_SIZE bytes" }
        require(publicKey.size == KEY_SIZE) { "A public key is $KEY_SIZE bytes" }
    }

    public companion object {
        /** Length of a private or public key. */
        public const val KEY_SIZE: Int = 32
    }
}

/**
 * Noise handshakes and transports of device-to-device sync. The native library
 * owns the Noise state and its keys; the caller owns framing, timeouts and
 * retries. A transport never rekeys: replace it with a new handshake.
 *
 * Every call blocks: call it on `Dispatchers.IO`. A failure throws
 * [NativeCryptoException]:
 *
 * - [NativeCryptoErrorCode.AUTHENTICATION_FAILED] for a wrong pre-shared or
 *   pinned key, a low-order key, or a message that fails decryption, such as
 *   a forged, replayed, reordered or truncated transport message;
 * - [NativeCryptoErrorCode.CRYPTO_FAILURE] for a malformed, short or
 *   out-of-turn handshake message, an unfinished handshake, or an exhausted
 *   nonce;
 * - [NativeCryptoErrorCode.INVALID_ARGUMENT] for a key of the wrong size or an
 *   oversized message, after which the session goes on;
 * - [NativeCryptoErrorCode.INVALID_SESSION] once the session is closed, spent,
 *   or ended by a failure;
 * - [NativeCryptoErrorCode.RESOURCE_LIMIT] while 256 Noise sessions are open.
 *
 * Any other failure of a read or write also ends the session.
 */
public object NativeCryptoNoise {
    /** Length of a pre-shared key. */
    public const val PSK_SIZE: Int = 32

    /** Length of a handshake hash. */
    public const val HASH_SIZE: Int = 32

    /** Largest Noise message. */
    public const val MAX_MESSAGE_SIZE: Int = 65_535

    /** Length of a ChaCha20-Poly1305 tag. */
    private const val TAG_SIZE: Int = 16

    /**
     * Largest payload of one handshake message, which also carries an
     * ephemeral key, an encrypted static key and two tags.
     */
    public const val MAX_HANDSHAKE_PAYLOAD_SIZE: Int =
        MAX_MESSAGE_SIZE - 2 * NativeNoiseKeyPair.KEY_SIZE - 2 * TAG_SIZE

    /** Largest payload of one transport message. */
    public const val MAX_TRANSPORT_PAYLOAD_SIZE: Int = MAX_MESSAGE_SIZE - TAG_SIZE

    /** Generates a static key pair from the system RNG. */
    public fun generateKeyPair(): NativeNoiseKeyPair = keyPair(EMPTY)

    /**
     * Returns the key pair of the static [privateKey], deriving its public key.
     * A key of the wrong size throws [NativeCryptoErrorCode.INVALID_ARGUMENT].
     */
    public fun keyPairOf(privateKey: ByteArray): NativeNoiseKeyPair {
        // The native side takes an empty key as a request for a new one.
        if (privateKey.size != NativeNoiseKeyPair.KEY_SIZE) {
            throw NativeCryptoException(KEY_PAIR_OPERATION, NativeCryptoErrorCode.INVALID_ARGUMENT)
        }
        return keyPair(privateKey)
    }

    /**
     * Starts a [NativeNoisePattern.XX_PSK3] handshake for pairing. Both sides
     * pass the same [psk] and [prologue].
     */
    public fun xxPsk3(
        initiator: Boolean,
        localPrivateKey: ByteArray,
        psk: ByteArray,
        prologue: ByteArray = EMPTY,
    ): NativeNoiseHandshake = open(
        pattern = NativeNoisePattern.XX_PSK3,
        initiator = initiator,
        localPrivateKey = localPrivateKey,
        remotePublicKey = EMPTY,
        psk = psk,
        prologue = prologue,
    )

    /**
     * Starts a [NativeNoisePattern.KK] handshake with the paired device whose
     * static key is [remotePublicKey]. Both sides pass the same [prologue].
     */
    public fun kk(
        initiator: Boolean,
        localPrivateKey: ByteArray,
        remotePublicKey: ByteArray,
        prologue: ByteArray = EMPTY,
    ): NativeNoiseHandshake = open(
        pattern = NativeNoisePattern.KK,
        initiator = initiator,
        localPrivateKey = localPrivateKey,
        remotePublicKey = remotePublicKey,
        psk = EMPTY,
        prologue = prologue,
    )

    private fun keyPair(privateKey: ByteArray): NativeNoiseKeyPair {
        val payload = NativeCrypto.call(
            operationName = KEY_PAIR_OPERATION,
            operation = NoiseKeyPairOperationProto(NoiseKeyPairRequestProto(privateKey)),
        ).requireBytes(KEY_PAIR_OPERATION)
        val result = decodeNativeCryptoPayload<NoiseKeyPairResultProto>(KEY_PAIR_OPERATION, payload)
        requireNativeCryptoKeyPairSizes(
            operation = KEY_PAIR_OPERATION,
            privateKey = result.privateKey,
            privateKeySize = NativeNoiseKeyPair.KEY_SIZE,
            publicKey = result.publicKey,
            publicKeySize = NativeNoiseKeyPair.KEY_SIZE,
        )
        return NativeNoiseKeyPair(
            privateKey = result.privateKey,
            publicKey = result.publicKey,
        )
    }

    // One argument per field of the open request.
    @Suppress("LongParameterList")
    private fun open(
        pattern: NativeNoisePattern,
        initiator: Boolean,
        localPrivateKey: ByteArray,
        remotePublicKey: ByteArray,
        psk: ByteArray,
        prologue: ByteArray,
    ): NativeNoiseHandshake {
        val handle = NativeCrypto.openHandle(
            operationName = HANDSHAKE_OPEN_OPERATION,
            operation = NoiseHandshakeStreamOpenOperationProto(
                NoiseHandshakeStreamOpenRequestProto(
                    pattern = pattern.proto,
                    initiator = initiator,
                    localPrivateKey = localPrivateKey,
                    remotePublicKey = remotePublicKey,
                    psk = psk,
                    prologue = prologue,
                ),
            ),
        )
        return NativeNoiseHandshake(pattern, handle)
    }

    internal const val KEY_PAIR_OPERATION: String = "noise_key_pair"
    internal const val HANDSHAKE_OPEN_OPERATION: String = "noise_handshake.stream_open"
    internal const val HANDSHAKE_WRITE_OPERATION: String = "noise_handshake_write"
    internal const val HANDSHAKE_READ_OPERATION: String = "noise_handshake_read"
    internal const val HANDSHAKE_STATUS_OPERATION: String = "noise_handshake_status"
    internal const val INTO_TRANSPORT_OPERATION: String = "noise_into_transport"
    internal const val TRANSPORT_WRITE_OPERATION: String = "noise_transport_write"
    internal const val TRANSPORT_READ_OPERATION: String = "noise_transport_read"
}

/**
 * One side of a Noise handshake.
 *
 * Exchange messages with [writeMessage] and [readMessage] in the pattern's
 * order until [isFinished], read [remoteStaticKey] and [handshakeHash], then
 * call [intoTransport]. An [NativeNoisePattern.XX_PSK3] initiator can't trust
 * [remoteStaticKey] yet; see [NativeNoisePattern.XX_PSK3]. [close] it in every
 * case; closing twice, or after [intoTransport], is a no-op. A failed [writeMessage] or [readMessage]
 * ends the handshake and wipes its keys, unless it failed with
 * [NativeCryptoErrorCode.INVALID_ARGUMENT].
 *
 * A handshake is not meant for concurrent use.
 */
public class NativeNoiseHandshake internal constructor(
    public val pattern: NativeNoisePattern,
    private val handle: Long,
) : AutoCloseable {
    private var closed = false

    /**
     * Writes the next handshake message carrying [payload], at most
     * [NativeCryptoNoise.MAX_HANDSHAKE_PAYLOAD_SIZE] bytes.
     */
    public fun writeMessage(payload: ByteArray = EMPTY): ByteArray = NativeCrypto.call(
        operationName = NativeCryptoNoise.HANDSHAKE_WRITE_OPERATION,
        operation = NoiseHandshakeWriteOperationProto(NoiseHandshakeWriteRequestProto(handle, payload)),
    ).requireBytes(NativeCryptoNoise.HANDSHAKE_WRITE_OPERATION)

    /** Reads the next handshake message and returns its payload. */
    public fun readMessage(message: ByteArray): ByteArray = NativeCrypto.call(
        operationName = NativeCryptoNoise.HANDSHAKE_READ_OPERATION,
        operation = NoiseHandshakeReadOperationProto(NoiseHandshakeReadRequestProto(handle, message)),
    ).requireBytes(NativeCryptoNoise.HANDSHAKE_READ_OPERATION)

    /** Whether every message of the pattern was written or read. */
    public fun isFinished(): Boolean = status().finished

    /**
     * The remote side's static public key, or `null` while it is not known yet.
     * A [NativeNoisePattern.KK] handshake knows it from the start. A finished
     * [NativeNoisePattern.XX_PSK3] initiator has it before the responder proved
     * it knows the pre-shared key.
     */
    public fun remoteStaticKey(): ByteArray? = status()
        .remoteStaticKey
        .takeIf { key -> key.isNotEmpty() }
        ?.requireNativeCryptoOutputSize(
            NativeCryptoNoise.HANDSHAKE_STATUS_OPERATION,
            NativeNoiseKeyPair.KEY_SIZE,
        )

    /**
     * The handshake hash, equal on both sides, for channel binding. Before
     * [isFinished] it throws [NativeCryptoErrorCode.CRYPTO_FAILURE], and the
     * handshake goes on.
     */
    public fun handshakeHash(): ByteArray {
        val status = status()
        if (!status.finished) {
            throw NativeCryptoException(
                operation = NativeCryptoNoise.HANDSHAKE_STATUS_OPERATION,
                code = NativeCryptoErrorCode.CRYPTO_FAILURE,
            )
        }
        return status.handshakeHash.requireNativeCryptoOutputSize(
            NativeCryptoNoise.HANDSHAKE_STATUS_OPERATION,
            NativeCryptoNoise.HASH_SIZE,
        )
    }

    /**
     * Spends this finished handshake and returns its transport. This handshake
     * is gone afterwards, whatever the outcome.
     */
    public fun intoTransport(): NativeNoiseTransport = NativeNoiseTransport(
        NativeCrypto.callHandle(
            operationName = NativeCryptoNoise.INTO_TRANSPORT_OPERATION,
            operation = NoiseIntoTransportOperationProto(NoiseIntoTransportRequestProto(handle)),
        ),
    )

    /** Releases the handshake and wipes its keys. */
    override fun close() {
        if (closed) return
        NativeCrypto.closeHandle(handle)
        closed = true
    }

    private fun status(): NoiseHandshakeStatusResultProto = decodeNativeCryptoPayload(
        operation = NativeCryptoNoise.HANDSHAKE_STATUS_OPERATION,
        payload = NativeCrypto.call(
            operationName = NativeCryptoNoise.HANDSHAKE_STATUS_OPERATION,
            operation = NoiseHandshakeStatusOperationProto(NoiseHandshakeStatusRequestProto(handle)),
        ).requireBytes(NativeCryptoNoise.HANDSHAKE_STATUS_OPERATION),
    )
}

/**
 * The encrypted channel a finished [NativeNoiseHandshake] turns into.
 *
 * Messages must be read in the order the other side wrote them, so the channel
 * below must be reliable and ordered. A failed [readMessage] or [writeMessage]
 * ends the transport and wipes its keys, unless it failed with
 * [NativeCryptoErrorCode.INVALID_ARGUMENT]. [close] it in every case; closing
 * twice is a no-op.
 *
 * A transport is not meant for concurrent use.
 */
public class NativeNoiseTransport internal constructor(
    private val handle: Long,
) : AutoCloseable {
    private var closed = false
    private var nextNonce = 0L

    /**
     * Encrypts [payload], at most [NativeCryptoNoise.MAX_TRANSPORT_PAYLOAD_SIZE]
     * bytes, into the next message.
     */
    public fun writeMessage(payload: ByteArray): ByteArray {
        val message = NativeCrypto.call(
            operationName = NativeCryptoNoise.TRANSPORT_WRITE_OPERATION,
            operation = NoiseTransportWriteOperationProto(NoiseTransportWriteRequestProto(handle, payload)),
        ).requireBytes(NativeCryptoNoise.TRANSPORT_WRITE_OPERATION)
        // A rejected write leaves the nonce as it was, and any other failure
        // ends the transport, so counting successful writes tracks it exactly.
        nextNonce++
        return message
    }

    /** Decrypts the next message and returns its payload. */
    public fun readMessage(message: ByteArray): ByteArray = NativeCrypto.call(
        operationName = NativeCryptoNoise.TRANSPORT_READ_OPERATION,
        operation = NoiseTransportReadOperationProto(NoiseTransportReadRequestProto(handle, message)),
    ).requireBytes(NativeCryptoNoise.TRANSPORT_READ_OPERATION)

    /**
     * The nonce of the next message this side writes, which is the number of
     * messages written so far. Replace the session with a new handshake well
     * before it grows large.
     */
    public fun sendNonce(): Long = nextNonce

    /** Releases the transport and wipes its keys. */
    override fun close() {
        if (closed) return
        NativeCrypto.closeHandle(handle)
        closed = true
    }
}
