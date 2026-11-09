package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.NOISE_HANDSHAKE_TIMEOUT_MS
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import com.artemchep.keyguard.nativecrypto.NativeNoiseHandshake
import com.artemchep.keyguard.nativecrypto.NativeNoisePattern
import com.artemchep.keyguard.nativecrypto.NativeNoiseTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An encrypted [ByteStream] over another one: each message is one Noise transport message.
 * Open it with [handshake]. [remoteStatic] and [handshakeHash] identify the peer and the
 * session; the enrollment derives its short authentication string from them.
 *
 * Reads and writes may run at the same time; writes keep their order, as the Noise nonces
 * need. A message that fails to decrypt ends the session: reads then return `null`. A
 * stream ends itself once it has written `maxSendNonce` messages, well before the 2^63 the
 * native nonce can count to; the session then reconnects with fresh keys.
 *
 * The native calls block, so they run on [Dispatchers.IO].
 */
class NoiseByteStream private constructor(
    private val inner: ByteStream,
    private val transport: NativeNoiseTransport,
    private val maxSendNonce: Long,
    /** The peer's static public key. */
    val remoteStatic: ByteArray,
    /** The handshake hash, equal on both sides. */
    val handshakeHash: ByteArray,
) : ByteStream {
    companion object {
        /** The largest message; the stream below must carry it plus [TAG_SIZE] bytes. */
        const val MAX_MESSAGE_SIZE: Int = NativeCryptoNoise.MAX_TRANSPORT_PAYLOAD_SIZE

        /** What Noise adds to each message. */
        const val TAG_SIZE: Int = NativeCryptoNoise.MAX_MESSAGE_SIZE - NativeCryptoNoise.MAX_TRANSPORT_PAYLOAD_SIZE

        /** 2^62 messages. */
        const val MAX_SEND_NONCE: Long = 1L shl 62

        /** Both sides of every Keyguard handshake bind it, followed by the id of the suite. */
        private val PROLOGUE: ByteArray = "keyguard/p2p/noise/v1".encodeToByteArray()

        /**
         * Runs a [suite] handshake over [stream] and returns the encrypted stream. The
         * initiator first sends the one-byte id of the suite, and the responder takes only
         * that one, see [NoiseSuite]. Every handshake message carries an empty payload. On
         * failure, or after [timeoutMs], closes [stream] and throws.
         *
         * @param remotePublicKey the peer's static key, required for [NoiseSuite.KK_HFS].
         * @param psk the pre-shared key, required for [NoiseSuite.XX_PSK3].
         * @param pinnedRemoteStatic the static key the peer must have, such as the one of a
         *   pairing code: checked as soon as the peer sends its key, before this side sends
         *   its own, so a peer with another key never learns it.
         * @param maxSendNonce how many messages the stream writes before it ends.
         * @throws TransportException when the stream ends or the time runs out, or a message
         * carries a payload; [NoiseSuiteException] when the initiator names another suite;
         * [NoisePinException] when the peer's key is not the pinned one; or
         * `NativeCryptoException` when the peer is not who it should be.
         */
        @Suppress("LongParameterList") // One argument per handshake input.
        suspend fun handshake(
            stream: ByteStream,
            suite: NoiseSuite,
            initiator: Boolean,
            localPrivateKey: ByteArray,
            remotePublicKey: ByteArray? = null,
            psk: ByteArray? = null,
            pinnedRemoteStatic: ByteArray? = null,
            timeoutMs: Long = NOISE_HANDSHAKE_TIMEOUT_MS,
            maxSendNonce: Long = MAX_SEND_NONCE,
        ): NoiseByteStream {
            // Kept outside of each `withContext`, which drops its result if the caller is
            // cancelled meanwhile, so a native handle is closed unless it reaches the caller.
            var handshake: NativeNoiseHandshake? = null
            var result: NoiseByteStream? = null
            var returned = false
            try {
                withTimeoutOrNull(timeoutMs) {
                    agreeOnSuite(stream, suite, initiator)
                    withContext(Dispatchers.IO) {
                        handshake = open(suite, initiator, localPrivateKey, remotePublicKey, psk)
                    }
                    val opened = checkNotNull(handshake)
                    exchange(stream, opened, initiator, pinnedRemoteStatic)
                    withContext(Dispatchers.IO) {
                        result = finish(stream, opened, maxSendNonce)
                    }
                }
                val opened = result ?: throw TransportException("The Noise handshake timed out")
                returned = true
                return opened
            } finally {
                // One non-cancellable block: a dispatcher switch back to a cancelled caller
                // would throw, and skip the rest.
                withContext(NonCancellable) {
                    // Closing the handshake after it turned into the transport does nothing.
                    handshake?.let { withContext(Dispatchers.IO) { it.close() } }
                    if (!returned) {
                        val opened = result
                        if (opened != null) opened.close() else stream.close()
                    }
                }
            }
        }

        /**
         * The initiator names the [suite]; the responder reads the name and takes only that
         * suite. A changed name gets past this, but not past the handshake: the prologue
         * binds it.
         */
        private suspend fun agreeOnSuite(stream: ByteStream, suite: NoiseSuite, initiator: Boolean) {
            if (initiator) {
                stream.write(byteArrayOf(suite.id))
                return
            }
            val named = stream.read() ?: throw TransportException("The stream closed during the handshake")
            if (named.size != 1 || named[0] != suite.id) throw NoiseSuiteException()
        }

        private fun open(
            suite: NoiseSuite,
            initiator: Boolean,
            localPrivateKey: ByteArray,
            remotePublicKey: ByteArray?,
            psk: ByteArray?,
        ): NativeNoiseHandshake {
            val prologue = PROLOGUE + suite.id
            return when (suite.pattern) {
                NativeNoisePattern.XX_PSK3 -> NativeCrypto.noise.xxPsk3(
                    initiator = initiator,
                    localPrivateKey = localPrivateKey,
                    psk = requireNotNull(psk) { "XXpsk3 needs a pre-shared key" },
                    prologue = prologue,
                )

                NativeNoisePattern.KK_HFS -> NativeCrypto.noise.kkHfs(
                    initiator = initiator,
                    localPrivateKey = localPrivateKey,
                    remotePublicKey = requireNotNull(remotePublicKey) { "KKhfs needs the peer's static key" },
                    prologue = prologue,
                )
            }
        }

        /** Writes and reads the handshake messages until the [handshake] is finished. */
        private suspend fun exchange(
            stream: ByteStream,
            handshake: NativeNoiseHandshake,
            initiator: Boolean,
            pinnedRemoteStatic: ByteArray?,
        ) {
            var writes = initiator
            while (!withContext(Dispatchers.IO) { handshake.isFinished() }) {
                if (writes) {
                    stream.write(withContext(Dispatchers.IO) { handshake.writeMessage() })
                } else {
                    val message = stream.read() ?: throw TransportException("The stream closed during the handshake")
                    val (payload, remote) = withContext(Dispatchers.IO) {
                        handshake.readMessage(message) to handshake.remoteStaticKey()
                    }
                    checkReceived(payload, remote, pinnedRemoteStatic)
                }
                writes = !writes
            }
        }

        /** Turns the finished [handshake] into the encrypted stream over [stream]. */
        private fun finish(
            stream: ByteStream,
            handshake: NativeNoiseHandshake,
            maxSendNonce: Long,
        ): NoiseByteStream {
            // Read both before the handshake turns into the transport.
            val remoteStatic = checkNotNull(handshake.remoteStaticKey()) { "Noise finished without a static key" }
            val handshakeHash = handshake.handshakeHash()
            return NoiseByteStream(stream, handshake.intoTransport(), maxSendNonce, remoteStatic, handshakeHash)
        }
        /**
         * Checks a received handshake message: every Keyguard handshake message is empty, as
         * a payload would only give a peer bytes to vary the handshake hash with, and the
         * peer's static key, once sent, must be the [pinned] one.
         */
        private fun checkReceived(payload: ByteArray, remote: ByteArray?, pinned: ByteArray?) {
            if (payload.isNotEmpty()) throw TransportException("A handshake message carried a payload")
            if (pinned != null && remote != null && !remote.contentEquals(pinned)) throw NoisePinException()
        }
    }

    private val readLock = Mutex()
    private val writeLock = Mutex()

    override suspend fun read(): ByteArray? = readLock.withLock {
        val message = inner.read() ?: return@withLock null
        // A failed decrypt ends the Noise session, so it ends the stream too.
        decryptOrNull(message) ?: run {
            close()
            null
        }
    }

    override suspend fun write(bytes: ByteArray) {
        require(bytes.size <= MAX_MESSAGE_SIZE) { "A message is at most $MAX_MESSAGE_SIZE bytes" }
        writeLock.withLock {
            val message = withContext(Dispatchers.IO) { encrypt(bytes) }
            inner.write(message)
        }
    }

    override suspend fun awaitWritable() {
        inner.awaitWritable()
    }

    override suspend fun close() {
        withContext(NonCancellable) {
            inner.close()
            withContext(Dispatchers.IO) { transport.close() }
        }
    }

    private fun encrypt(bytes: ByteArray): ByteArray {
        if (transport.sendNonce() >= maxSendNonce) {
            transport.close()
            // The session wrote its last message.
            throw NativeCryptoException(
                operation = "noise_transport_write",
                code = NativeCryptoErrorCode.INVALID_SESSION,
            )
        }
        return transport.writeMessage(bytes)
    }

    private suspend fun decryptOrNull(message: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        try {
            transport.readMessage(message)
        } catch (expected: NativeCryptoException) {
            null
        }
    }
}

/** The peer of a handshake sent another static key than the pinned one. */
class NoisePinException : Exception("The peer's static key is not the pinned one")

/**
 * The Noise suites of Keyguard, each named on the wire by its one-byte [id]: every Keyguard
 * Noise stream starts with the id, and the prologue binds it, see [NoiseByteStream.handshake].
 * A new suite, such as one with another KEM or larger keys, takes a new id; an initiator
 * picks one that the peer lists, and the responder never takes a suite it was not asked for.
 */
enum class NoiseSuite(
    val id: Byte,
    val pattern: NativeNoisePattern,
) {
    /** Pairing: `Noise_XXpsk3_25519_ChaChaPoly_SHA256`. */
    XX_PSK3(id = 1, pattern = NativeNoisePattern.XX_PSK3),

    /** Sessions: `Noise_KKhfs_25519+Kyber1024_ChaChaPoly_SHA256`, with ML-KEM-1024. */
    KK_HFS(id = 2, pattern = NativeNoisePattern.KK_HFS),
}

/** The initiator of a handshake named another suite than the one the responder takes. */
class NoiseSuiteException : Exception("The peer named another Noise suite")
