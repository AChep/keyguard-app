package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.io.encodeHex
import app.keemobile.kotpass.resources.Salsa20Res
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeStreamCipherAlgorithm
import kotlin.test.Test

class Salsa20Spec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {
        describe("Salsa20/20 stream cipher") {
            Salsa20Res.SalsaTestCases
                .filter { testCase -> testCase.rounds == 20 && testCase.key.length == 64 }
                .forEach { testCase ->
                    it("matches a 256-bit-key published vector") {
                        val output = NativeCryptoPrimitives.streamCipherXorAtOffset(
                            algorithm = NativeStreamCipherAlgorithm.SALSA20,
                            key = testCase.key.decodeHexToArray(),
                            nonce = testCase.iv.decodeHexToArray(),
                            offset = 0,
                            data = testCase.plaintext.decodeHexToArray(),
                        )
                        output.encodeHex() shouldBe testCase.cipher
                    }
                }

            it("uses deterministic absolute offsets across block boundaries") {
                val key = ByteArray(32) { index -> index.toByte() }
                val nonce = ByteArray(8) { index -> (index * 3).toByte() }
                val input = ByteArray(130) { index -> (index * 7).toByte() }
                val oneShot = xor(key, nonce, 0, input)
                val partitioned = xor(key, nonce, 0, input.copyOfRange(0, 63)) +
                    xor(key, nonce, 63, input.copyOfRange(63, 64)) +
                    xor(key, nonce, 64, input.copyOfRange(64, 65)) +
                    xor(key, nonce, 65, input.copyOfRange(65, input.size))
                partitioned shouldBe oneShot
                xor(key, nonce, 0, oneShot) shouldBe input
            }
        }
    }

    private fun xor(
        key: ByteArray,
        nonce: ByteArray,
        offset: Long,
        input: ByteArray,
    ): ByteArray = NativeCryptoPrimitives.streamCipherXorAtOffset(
        algorithm = NativeStreamCipherAlgorithm.SALSA20,
        key = key,
        nonce = nonce,
        offset = offset,
        data = input,
    )
}
