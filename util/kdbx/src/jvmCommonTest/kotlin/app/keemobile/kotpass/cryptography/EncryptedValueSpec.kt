package app.keemobile.kotpass.cryptography

import app.keemobile.kotpass.io.encodeHex
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import kotlin.experimental.xor

class EncryptedValueSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Encrypted value") {
        val valueBytes = "strvalue".toByteArray()
        val encValueBytes = "strvalue".toByteArray()
        val saltBytes = ByteArray(valueBytes.size)
        for (i in saltBytes.indices) {
            saltBytes[i] = i.toByte()
            encValueBytes[i] = encValueBytes[i] xor i.toByte()
        }

        it("Decrypts salted value in string") {
            EncryptedValue(encValueBytes, saltBytes)
                .text shouldBe "strvalue"
        }

        it("Returns string in binary") {
            EncryptedValue(encValueBytes, saltBytes)
                .getBinary() shouldBe valueBytes
        }

        it("Calculates SHA256 hash") {
            EncryptedValue(encValueBytes, saltBytes)
                .getHash()
                .encodeHex() shouldBe "1f5c3ef76d43e72ee2c5216c36187c799b153cab3d0cb63a6f3ecccc2627f535"
        }

        it("Creates value from string") {
            EncryptedValue
                .fromString("test")
                .text shouldBe "test"
        }

        it("Creates value from binary") {
            EncryptedValue
                .fromBinary("test".toByteArray())
                .text shouldBe "test"
        }

        it("Returns byte length") {
            EncryptedValue
                .fromBinary("test".toByteArray())
                .byteLength shouldBe 4
        }

        it("Can change salt") {
            val value = EncryptedValue.fromString("test")

            value.text shouldBe "test"

            value.setSalt(byteArrayOf(1, 2, 3, 4))

            value.text shouldBe "test"
        }

        it("Returns protected value as base64 string") {
            val value = EncryptedValue
                .fromBinary("test".toByteArray())
            value.setSalt(byteArrayOf(1, 2, 3, 4))

            value.toString() shouldBe "dWdwcA=="
        }

        it("Creates a value from base64") {
            EncryptedValue
                .fromBase64("aGVsbG8=")
                .text shouldBe "hello"
        }

        it("Returns base64 of the value") {
            EncryptedValue
                .fromString("hello")
                .toBase64() shouldBe "aGVsbG8="
        }
    }
    }
}
