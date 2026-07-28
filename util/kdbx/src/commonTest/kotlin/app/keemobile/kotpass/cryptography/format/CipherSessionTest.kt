package app.keemobile.kotpass.cryptography.format

import com.artemchep.keyguard.nativecrypto.NATIVE_CRYPTO_STREAM_CHUNK_BYTES
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals

class CipherSessionTest {
    @Test
    fun incrementalSessionsAreIndependentOfInputChunking() {
        val providers = BaseCiphers.entries + TwofishCipher
        val key = ByteArray(32) { index -> (index * 13).toByte() }
        val plaintext = ByteArray(64 * 1024 + 257) { index -> (index * 31).toByte() }
        val chunks = intArrayOf(1, 15, 16, 17, 63, 64, 65, 1023, 8191)

        providers.forEach { provider ->
            val iv = ByteArray(provider.ivLength.toInt()) { index -> (index * 7).toByte() }
            val expectedCiphertext =
                provider.createEncryptor(key, iv).use { session ->
                    transform(session, plaintext, intArrayOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES))
                }
            val actualCiphertext =
                provider.createEncryptor(key, iv).use { session ->
                    transform(session, plaintext, chunks)
                }
            assertContentEquals(
                expectedCiphertext,
                actualCiphertext,
                "Incremental encryption differs for ${provider.uuid}",
            )

            val actualPlaintext =
                provider.createDecryptor(key, iv).use { session ->
                    transform(session, actualCiphertext, chunks.reversedArray())
                }
            assertContentEquals(
                plaintext,
                actualPlaintext,
                "Incremental decryption differs for ${provider.uuid}",
            )
        }
    }

    private fun transform(
        session: CipherSession,
        input: ByteArray,
        chunks: IntArray,
    ): ByteArray {
        val output = Buffer()
        var offset = 0
        var chunkIndex = 0
        while (offset < input.size) {
            val length = minOf(chunks[chunkIndex % chunks.size], input.size - offset)
            output.write(session.update(input, offset, length))
            offset += length
            chunkIndex++
        }
        output.write(session.finish())
        return output.readByteArray()
    }
}
