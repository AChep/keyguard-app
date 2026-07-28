package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Permanent differential coverage for native compatibility.
 *
 * Bouncy Castle deliberately remains on the test classpath as an independent
 * compatibility oracle; none of these helpers are reachable from production.
 */
class SshAgentTransportBouncyCastleDifferentialTest {
    @Test
    fun `native transport ciphertext exactly matches BC`() {
        payloadSizes.forEachIndexed { index, size ->
            val payload = payload(size, seed = index)
            val counter = index.toLong()
            val nonce = nonce(counter)
            val header = header(counter, payload.size + AEAD_TAG_LENGTH)

            val expected = bouncyCastleChaCha20Poly1305(
                encrypt = true,
                key = key,
                nonce = nonce,
                header = header,
                payload = payload,
            )
            val actual = NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Encrypt(
                key = key,
                nonce = nonce,
                header = header,
                payload = payload,
            )

            assertContentEquals(expected, actual, "payload size=$size")
            assertContentEquals(
                expected = payload,
                actual = bouncyCastleChaCha20Poly1305(
                    encrypt = false,
                    key = key,
                    nonce = nonce,
                    header = header,
                    payload = actual,
                ),
                message = "payload size=$size",
            )
        }
    }

    @Test
    fun `BC transport ciphertext decrypts with native crypto`() {
        payloadSizes.forEachIndexed { index, size ->
            val payload = payload(size, seed = index + 97)
            val counter = counters[index]
            val nonce = nonce(counter)
            val header = header(counter, payload.size + AEAD_TAG_LENGTH)
            val ciphertext = bouncyCastleChaCha20Poly1305(
                encrypt = true,
                key = key,
                nonce = nonce,
                header = header,
                payload = payload,
            )

            assertContentEquals(
                expected = payload,
                actual = NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Decrypt(
                    key = key,
                    nonce = nonce,
                    header = header,
                    payload = ciphertext,
                ),
                message = "payload size=$size",
            )
        }
    }

    @Test
    fun `native transport rejects a tampered BC authentication tag`() {
        val payload = payload(65, seed = 211)
        val nonce = nonce(counter = 42)
        val header = header(counter = 42, payloadLength = payload.size + AEAD_TAG_LENGTH)
        val ciphertext = bouncyCastleChaCha20Poly1305(
            encrypt = true,
            key = key,
            nonce = nonce,
            header = header,
            payload = payload,
        ).also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }

        val error = assertFailsWith<NativeCryptoException> {
            NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Decrypt(
                key = key,
                nonce = nonce,
                header = header,
                payload = ciphertext,
            )
        }

        assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, error.code)
    }

    private fun bouncyCastleChaCha20Poly1305(
        encrypt: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        header: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(
            encrypt,
            AEADParameters(
                KeyParameter(key),
                AEAD_TAG_LENGTH * 8,
                nonce,
                header,
            ),
        )
        val output = ByteArray(cipher.getOutputSize(payload.size))
        var written = cipher.processBytes(payload, 0, payload.size, output, 0)
        written += cipher.doFinal(output, written)
        return output.copyOf(written)
    }

    private fun header(
        counter: Long,
        payloadLength: Int,
    ): ByteArray = ByteBuffer.allocate(FRAME_HEADER_LENGTH)
        .order(ByteOrder.BIG_ENDIAN)
        .put(magic)
        .put(SshAgentTcpProtocol.PROTOCOL_VERSION.toByte())
        .put(SshAgentTcpProtocol.FRAME_TYPE_PACKET.toByte())
        .putLong(counter)
        .putInt(payloadLength)
        .array()

    private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(NONCE_LENGTH)
        .order(ByteOrder.BIG_ENDIAN)
        .put(noncePrefix)
        .putLong(counter)
        .array()

    private fun payload(size: Int, seed: Int): ByteArray =
        ByteArray(size) { index -> (index * 31 + seed).toByte() }

    private companion object {
        private const val FRAME_HEADER_LENGTH = 18
        private const val NONCE_LENGTH = 12
        private const val AEAD_TAG_LENGTH = 16

        private val magic = "KSAG".encodeToByteArray()
        private val key = ByteArray(32) { index -> (index * 7 + 3).toByte() }
        private val noncePrefix = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        private val payloadSizes = listOf(
            0,
            1,
            15,
            16,
            63,
            64,
            65,
            4096,
            SshAgentTcpProtocol.MAX_FRAME_PAYLOAD_SIZE,
        )
        private val counters = listOf(
            0L,
            1L,
            Long.MAX_VALUE,
            -1L,
            Long.MIN_VALUE,
            0x0102_0304_0506_0708L,
            -0x0102_0304_0506_0708L,
            42L,
            256L,
        )
    }
}
