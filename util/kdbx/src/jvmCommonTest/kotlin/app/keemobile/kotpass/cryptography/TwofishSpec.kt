@file:Suppress("DEPRECATION", "SpellCheckingInspection")

package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.CipherSession
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.errors.CryptoError.InvalidCipherText
import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.resources.TwofishCbcPaddedRes
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TwofishSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {
        describe("Twofish block cipher CBC/PKCS7") {
            for (testCase in TwofishCbcPaddedRes.Items) {
                val key = testCase.key.decodeHexToArray()
                val answers = testCase.answers.map { (plainText, cipherText) ->
                    plainText.decodeHexToArray() to cipherText.decodeHexToArray()
                }

                it("encrypts published vectors for a ${key.size * 8}-bit key") {
                    for ((plainText, cipherText) in answers) {
                        transformTwofish(true, key, TwofishCbcPaddedRes.IV, plainText) shouldBe cipherText
                    }
                }

                it("decrypts published vectors for a ${key.size * 8}-bit key") {
                    for ((plainText, cipherText) in answers) {
                        transformTwofish(false, key, TwofishCbcPaddedRes.IV, cipherText) shouldBe plainText
                    }
                }

                it("matches the permanent BC test oracle for a ${key.size * 8}-bit key") {
                    val plainText = answers.last().first
                    transformTwofish(true, key, TwofishCbcPaddedRes.IV, plainText) shouldBe
                        processWithBouncyCastle(true, key, TwofishCbcPaddedRes.IV, plainText)
                }
            }

            it("rejects corrupted PKCS7 padding") {
                val key = TwofishCbcPaddedRes.Items.first().key.decodeHexToArray()
                val ciphertext = transformTwofish(true, key, TwofishCbcPaddedRes.IV, byteArrayOf(1, 2, 3))
                ciphertext[ciphertext.lastIndex] = 0
                assertFailsWith<InvalidCipherText> {
                    transformTwofish(false, key, TwofishCbcPaddedRes.IV, ciphertext)
                }
            }

            it("streams bodies larger than the control envelope") {
                val key = ByteArray(32) { index -> index.toByte() }
                val input = ByteArray(15 * 1024 * 1024 + 1) { index -> (index * 31).toByte() }
                val encrypted = transformTwofish(true, key, TwofishCbcPaddedRes.IV, input)
                transformTwofish(false, key, TwofishCbcPaddedRes.IV, encrypted) shouldBe input
            }

            it("decodes and encodes a KeePass 4.x Twofish file") {
                val credentials = Credentials.from(EncryptedValue.fromString("1"))
                val cipherProviders = BaseCiphers.entries + TwofishCipher
                var database = KeePassDatabase.decode(
                    inputStream = ClassLoader.getSystemResourceAsStream("ver4_twofish.kdbx")!!,
                    credentials = credentials,
                    cipherProviders = cipherProviders,
                )
                database.content.group.name shouldBe "New"

                val data = ByteArrayOutputStream()
                    .apply { database.encode(this, cipherProviders = cipherProviders) }
                    .toByteArray()
                database = KeePassDatabase.decode(
                    inputStream = ByteArrayInputStream(data),
                    credentials = credentials,
                    cipherProviders = cipherProviders,
                )
                database.content.group.name shouldBe "New"
            }
        }
    }

    private fun transformTwofish(
        encrypt: Boolean,
        key: ByteArray,
        iv: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val session =
            if (encrypt) {
                TwofishCipher.createEncryptor(key, iv)
            } else {
                TwofishCipher.createDecryptor(key, iv)
            }
        return session.use { transform ->
            transformInChunks(transform, input)
        }
    }

    private fun transformInChunks(
        session: CipherSession,
        input: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            var offset = 0
            while (offset < input.size) {
                val length = minOf(64 * 1024, input.size - offset)
                output.write(session.update(input, offset, length))
                offset += length
            }
            output.write(session.finish())
            output.toByteArray()
        }

    private fun processWithBouncyCastle(
        encrypt: Boolean,
        key: ByteArray,
        iv: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher(TwofishEngine()), PKCS7Padding())
        cipher.init(encrypt, ParametersWithIV(KeyParameter(key), iv))
        val output = ByteArray(cipher.getOutputSize(input.size))
        var length = cipher.processBytes(input, 0, input.size, output, 0)
        length += cipher.doFinal(output, length)
        return output.copyOf(length)
    }
}
