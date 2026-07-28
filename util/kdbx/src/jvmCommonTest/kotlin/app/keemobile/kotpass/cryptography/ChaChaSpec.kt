package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.io.decodeHexToArray
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeStreamCipherAlgorithm
import kotlin.test.Test

class ChaChaSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {
        describe("IETF ChaCha20 stream cipher") {
            it("matches RFC 8439 section 2.4.2") {
                val plaintext = (
                    "Ladies and Gentlemen of the class of '99: If I could offer you only " +
                        "one tip for the future, sunscreen would be it."
                    ).encodeToByteArray()
                val expected = (
                    "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                        "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                        "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                        "5af90bbf74a35be6b40b8eedf2785e42874d"
                    ).decodeHexToArray()
                xor(
                    key = (0..31).map(Int::toByte).toByteArray(),
                    nonce = "000000000000004a00000000".decodeHexToArray(),
                    offset = 64,
                    input = plaintext,
                ) shouldBe expected
            }

            it("uses deterministic absolute offsets across block boundaries") {
                val key = ByteArray(32) { index -> index.toByte() }
                val nonce = ByteArray(12) { index -> (index * 3).toByte() }
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
        algorithm = NativeStreamCipherAlgorithm.CHACHA20,
        key = key,
        nonce = nonce,
        offset = offset,
        data = input,
    )
}
