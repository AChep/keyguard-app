@file:Suppress("SpellCheckingInspection")

package app.keemobile.kotpass.cryptography.padding

import app.keemobile.kotpass.io.decodeHexToArray
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe

class PKCS7PaddingSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("PKCS7 padding") {
        it("Adds padding value") {
            val one = "ffffff0505050505".decodeHexToArray()
            val two = "0000000004040404".decodeHexToArray()

            var input = "ffffff0000000000".decodeHexToArray()
            PKCS7Padding.addPadding(input, 3)

            input shouldBe one
            PKCS7Padding.padCount(input) shouldBe 5

            input = ByteArray(8)
            PKCS7Padding.addPadding(input, 4)

            input shouldBe two
            PKCS7Padding.padCount(input) shouldBe 4
        }
    }
    }
}
