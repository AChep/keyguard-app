@file:Suppress("SpellCheckingInspection")

package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.cryptography.block.BlockCipherMode
import app.keemobile.kotpass.cryptography.block.PaddedBufferedBlockCipher
import app.keemobile.kotpass.cryptography.engines.TwofishEngine
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.cryptography.padding.PKCS7Padding
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.resources.TwofishCbcPaddedRes
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TwofishSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Twofish block cipher CBC/PKCS7") {
        for (testCase in TwofishCbcPaddedRes.Items) {
            it("Encrypts test vectors for key ${testCase.key}") {
                val answers = testCase.answers.map { (plainTxt, cipherTxt) ->
                    plainTxt.decodeHexToArray() to cipherTxt.decodeHexToArray()
                }
                val cipher = PaddedBufferedBlockCipher(
                    TwofishEngine(),
                    BlockCipherMode.CBC(TwofishCbcPaddedRes.IV),
                    PKCS7Padding
                )
                cipher.init(true, testCase.key.decodeHexToArray())

                for ((plainTxt, cipherTxt) in answers) {
                    cipher.reset()
                    val result = cipher.processBytes(plainTxt)

                    result shouldBe cipherTxt
                }
            }

            it("Decrypts test vectors for key ${testCase.key}") {
                val answers = testCase.answers.map { (plainTxt, cipherTxt) ->
                    plainTxt.decodeHexToArray() to cipherTxt.decodeHexToArray()
                }
                val cipher = PaddedBufferedBlockCipher(
                    TwofishEngine(),
                    BlockCipherMode.CBC(TwofishCbcPaddedRes.IV),
                    PKCS7Padding
                )
                cipher.init(false, testCase.key.decodeHexToArray())

                for ((plainTxt, cipherTxt) in answers) {
                    cipher.reset()
                    val result = cipher.processBytes(cipherTxt)

                    result shouldBe plainTxt
                }
            }
        }

        it("Decodes/encodes KeePass 4.x file with Twofish cipher") {
            val credentials = Credentials.from(EncryptedValue.fromString("1"))
            val cipherProviders = BaseCiphers.entries + TwofishCipher
            var database = KeePassDatabase.decode(
                inputStream = ClassLoader.getSystemResourceAsStream("ver4_twofish.kdbx")!!,
                credentials = credentials,
                cipherProviders = cipherProviders
            )
            database.content.group.name shouldBe "New"

            val data = ByteArrayOutputStream()
                .apply { database.encode(this, cipherProviders = cipherProviders) }
                .toByteArray()
            database = KeePassDatabase.decode(
                inputStream = ByteArrayInputStream(data),
                credentials = credentials,
                cipherProviders = cipherProviders
            )
            database.content.group.name shouldBe "New"
        }
    }
    }
}
