package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.crypto.util.hkdfSha256
import com.artemchep.keyguard.util.readNBytesCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

internal object SshAgentTcpProtocol {
    const val PROTOCOL_VERSION = 2

    const val SESSION_ID_LENGTH = 16
    const val SESSION_SECRET_LENGTH = 32
    const val CHALLENGE_LENGTH = 32

    const val FRAME_TYPE_TOOL_HELLO = 1
    const val FRAME_TYPE_APP_HELLO = 2
    const val FRAME_TYPE_PACKET = 3

    const val MAX_FRAME_PAYLOAD_SIZE = 1024 * 1024

    private val MAGIC = byteArrayOf(
        'K'.code.toByte(),
        'S'.code.toByte(),
        'A'.code.toByte(),
        'G'.code.toByte(),
    )

    fun openAsApp(
        input: InputStream,
        output: OutputStream,
        sessionId: ByteArray,
        sessionSecret: ByteArray,
        randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
    ): Channel {
        val channel = Channel(
            input = DataInputStream(BufferedInputStream(input)),
            output = BufferedOutputStream(output),
            sessionId = sessionId,
            sessionSecret = sessionSecret,
            role = Role.APP,
        )
        channel.performAppHandshake(randomBytes)
        return channel
    }

    fun openAsTool(
        input: InputStream,
        output: OutputStream,
        sessionId: ByteArray,
        sessionSecret: ByteArray,
        randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
    ): Channel {
        val channel = Channel(
            input = DataInputStream(BufferedInputStream(input)),
            output = BufferedOutputStream(output),
            sessionId = sessionId,
            sessionSecret = sessionSecret,
            role = Role.TOOL,
        )
        channel.performToolHandshake(randomBytes)
        return channel
    }

    internal class Channel(
        private val input: DataInputStream,
        private val output: BufferedOutputStream,
        private val sessionId: ByteArray,
        sessionSecret: ByteArray,
        role: Role,
    ) : SshAgentPacketChannel {
        private var sendCounter = 0L
        private var receiveCounter = 0L

        private val sendKey: ByteArray
        private val receiveKey: ByteArray
        private val sendNoncePrefix: ByteArray
        private val receiveNoncePrefix: ByteArray

        init {
            require(sessionId.size == SESSION_ID_LENGTH) {
                "Session ID must be $SESSION_ID_LENGTH bytes"
            }
            require(sessionSecret.size == SESSION_SECRET_LENGTH) {
                "Session secret must be $SESSION_SECRET_LENGTH bytes"
            }

            val sendLabel = when (role) {
                Role.TOOL -> "tool-to-app"
                Role.APP -> "app-to-tool"
            }
            val receiveLabel = when (role) {
                Role.TOOL -> "app-to-tool"
                Role.APP -> "tool-to-app"
            }

            sendKey = hkdfSha256(
                seed = sessionSecret,
                salt = sessionId,
                info = "keyguard-android-ssh-agent:$sendLabel:key".encodeToByteArray(),
                length = SESSION_SECRET_LENGTH,
            )
            receiveKey = hkdfSha256(
                seed = sessionSecret,
                salt = sessionId,
                info = "keyguard-android-ssh-agent:$receiveLabel:key".encodeToByteArray(),
                length = SESSION_SECRET_LENGTH,
            )
            sendNoncePrefix = hkdfSha256(
                seed = sessionSecret,
                salt = sessionId,
                info = "keyguard-android-ssh-agent:$sendLabel:nonce".encodeToByteArray(),
                length = 4,
            )
            receiveNoncePrefix = hkdfSha256(
                seed = sessionSecret,
                salt = sessionId,
                info = "keyguard-android-ssh-agent:$receiveLabel:nonce".encodeToByteArray(),
                length = 4,
            )
        }

        fun performAppHandshake(
            randomBytes: (Int) -> ByteArray,
        ) {
            val helloPayload = readFrame(expectedType = FRAME_TYPE_TOOL_HELLO)
            require(helloPayload.size == SESSION_ID_LENGTH + CHALLENGE_LENGTH) {
                "Invalid tool hello payload size=${helloPayload.size}"
            }
            val toolSessionId = helloPayload.copyOfRange(0, SESSION_ID_LENGTH)
            require(toolSessionId.contentEquals(sessionId)) {
                "Tool hello session id mismatch"
            }
            val toolChallenge = helloPayload.copyOfRange(
                SESSION_ID_LENGTH,
                SESSION_ID_LENGTH + CHALLENGE_LENGTH,
            )
            val appChallenge = randomBytes(CHALLENGE_LENGTH)
            val payload = ByteArray(SESSION_ID_LENGTH + CHALLENGE_LENGTH + CHALLENGE_LENGTH)
            System.arraycopy(sessionId, 0, payload, 0, SESSION_ID_LENGTH)
            System.arraycopy(toolChallenge, 0, payload, SESSION_ID_LENGTH, CHALLENGE_LENGTH)
            System.arraycopy(
                appChallenge,
                0,
                payload,
                SESSION_ID_LENGTH + CHALLENGE_LENGTH,
                CHALLENGE_LENGTH,
            )
            writeFrame(
                type = FRAME_TYPE_APP_HELLO,
                payload = payload,
            )
        }

        fun performToolHandshake(
            randomBytes: (Int) -> ByteArray,
        ) {
            val toolChallenge = randomBytes(CHALLENGE_LENGTH)
            val payload = ByteArray(SESSION_ID_LENGTH + CHALLENGE_LENGTH)
            System.arraycopy(sessionId, 0, payload, 0, SESSION_ID_LENGTH)
            System.arraycopy(toolChallenge, 0, payload, SESSION_ID_LENGTH, CHALLENGE_LENGTH)
            writeFrame(
                type = FRAME_TYPE_TOOL_HELLO,
                payload = payload,
            )

            val appHello = readFrame(expectedType = FRAME_TYPE_APP_HELLO)
            require(appHello.size == SESSION_ID_LENGTH + CHALLENGE_LENGTH + CHALLENGE_LENGTH) {
                "Invalid app hello payload size=${appHello.size}"
            }
            val appSessionId = appHello.copyOfRange(0, SESSION_ID_LENGTH)
            require(appSessionId.contentEquals(sessionId)) {
                "App hello session id mismatch"
            }
            val echoedChallenge = appHello.copyOfRange(
                SESSION_ID_LENGTH,
                SESSION_ID_LENGTH + CHALLENGE_LENGTH,
            )
            require(echoedChallenge.contentEquals(toolChallenge)) {
                "App hello challenge mismatch"
            }
        }

        override fun readPacket(): ByteArray? = try {
            readFrame(expectedType = FRAME_TYPE_PACKET)
        } catch (_: EOFException) {
            null
        }

        override fun writePacket(
            packet: ByteArray,
        ) {
            require(packet.size <= MAX_FRAME_PAYLOAD_SIZE) {
                "Packet too large: ${packet.size}"
            }
            writeFrame(
                type = FRAME_TYPE_PACKET,
                payload = packet,
            )
        }

        private fun writeFrame(
            type: Int,
            payload: ByteArray,
        ) {
            require(payload.size <= MAX_FRAME_PAYLOAD_SIZE) {
                "Frame payload too large: ${payload.size}"
            }
            val counter = sendCounter
            val header = buildHeader(
                type = type,
                counter = counter,
                payloadLength = payload.size + 16,
            )
            val ciphertext = encrypt(
                key = sendKey,
                noncePrefix = sendNoncePrefix,
                counter = counter,
                aad = header,
                payload = payload,
            )
            output.write(header)
            output.write(ciphertext)
            output.flush()
            sendCounter = counter + 1
        }

        private fun readFrame(
            expectedType: Int,
        ): ByteArray {
            val header = readHeader()
                ?: throw EOFException("Unexpected end of SSH agent stream")
            validateHeader(header, expectedType)

            val ciphertext = input.readNBytesCompat(header.payloadLength)
            require(ciphertext.size == header.payloadLength) {
                "Unexpected end of SSH agent frame"
            }

            val plaintext = decrypt(
                key = receiveKey,
                noncePrefix = receiveNoncePrefix,
                counter = header.counter,
                aad = header.raw,
                payload = ciphertext,
            )
            receiveCounter = header.counter + 1
            return plaintext
        }

        private fun validateHeader(
            header: FrameHeader,
            expectedType: Int,
        ) {
            require(header.version == PROTOCOL_VERSION) {
                "Unsupported SSH agent version=${header.version}"
            }
            require(header.type == expectedType) {
                "Unexpected SSH agent frame type=${header.type} expected=$expectedType"
            }
            require(header.counter == receiveCounter) {
                "Invalid SSH agent counter=${header.counter} expected=$receiveCounter"
            }
            require(header.payloadLength in 16..(MAX_FRAME_PAYLOAD_SIZE + 16)) {
                "Invalid SSH agent payload length=${header.payloadLength}"
            }
        }

        private fun readHeader(): FrameHeader? {
            val prefix = ByteArray(4)
            val first = input.read()
            if (first == -1) {
                return null
            }
            prefix[0] = first.toByte()
            input.readFully(prefix, 1, prefix.size - 1)
            require(prefix.contentEquals(MAGIC)) {
                "Invalid secure SSH agent frame magic"
            }

            val rest = ByteArray(1 + 1 + 8 + 4)
            input.readFully(rest)
            val raw = ByteArray(prefix.size + rest.size)
            System.arraycopy(prefix, 0, raw, 0, prefix.size)
            System.arraycopy(rest, 0, raw, prefix.size, rest.size)
            val buffer = ByteBuffer.wrap(rest)
                .order(ByteOrder.BIG_ENDIAN)
            val version = buffer.get().toInt() and 0xff
            val type = buffer.get().toInt() and 0xff
            val counter = buffer.long
            val payloadLength = buffer.int
            return FrameHeader(
                raw = raw,
                version = version,
                type = type,
                counter = counter,
                payloadLength = payloadLength,
            )
        }
    }

    private data class FrameHeader(
        val raw: ByteArray,
        val version: Int,
        val type: Int,
        val counter: Long,
        val payloadLength: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as FrameHeader

            if (version != other.version) return false
            if (type != other.type) return false
            if (counter != other.counter) return false
            if (payloadLength != other.payloadLength) return false
            if (!raw.contentEquals(other.raw)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + type
            result = 31 * result + counter.hashCode()
            result = 31 * result + payloadLength
            result = 31 * result + raw.contentHashCode()
            return result
        }
    }

    internal enum class Role {
        TOOL,
        APP,
    }

    private fun buildHeader(
        type: Int,
        counter: Long,
        payloadLength: Int,
    ): ByteArray = ByteBuffer.allocate(4 + 1 + 1 + 8 + 4)
        .order(ByteOrder.BIG_ENDIAN)
        .put(MAGIC)
        .put(PROTOCOL_VERSION.toByte())
        .put(type.toByte())
        .putLong(counter)
        .putInt(payloadLength)
        .array()

    private fun encrypt(
        key: ByteArray,
        noncePrefix: ByteArray,
        counter: Long,
        aad: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(
            true,
            AEADParameters(
                KeyParameter(key),
                128,
                buildNonce(noncePrefix, counter),
                aad,
            ),
        )
        val output = ByteArray(cipher.getOutputSize(payload.size))
        var written = cipher.processBytes(payload, 0, payload.size, output, 0)
        written += cipher.doFinal(output, written)
        return output.copyOf(written)
    }

    private fun decrypt(
        key: ByteArray,
        noncePrefix: ByteArray,
        counter: Long,
        aad: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(
            false,
            AEADParameters(
                KeyParameter(key),
                128,
                buildNonce(noncePrefix, counter),
                aad,
            ),
        )
        val output = ByteArray(cipher.getOutputSize(payload.size))
        return try {
            var written = cipher.processBytes(payload, 0, payload.size, output, 0)
            written += cipher.doFinal(output, written)
            output.copyOf(written)
        } catch (e: InvalidCipherTextException) {
            throw IllegalArgumentException("SSH agent payload authentication failed", e)
        }
    }

    private fun buildNonce(
        prefix: ByteArray,
        counter: Long,
    ): ByteArray = ByteBuffer.allocate(12)
        .order(ByteOrder.BIG_ENDIAN)
        .put(prefix)
        .putLong(counter)
        .array()
}

private fun secureRandomBytes(
    size: Int,
): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)
