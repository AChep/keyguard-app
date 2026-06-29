package app.keemobile.kotpass.models

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe

class EntryValueSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("EntryValue") {
        it("isEmpty check properly evaluates empty string") {
            val emptyPlain = EntryValue.Plain("")
            val emptyEncrypted = EntryValue.Encrypted(EncryptedValue.fromString(""))
            val somePlain = EntryValue.Encrypted(EncryptedValue.fromString("123"))
            val someEncrypted = EntryValue.Encrypted(EncryptedValue.fromString("123"))

            emptyPlain.isEmpty() shouldBe true
            emptyEncrypted.isEmpty() shouldBe true

            somePlain.isEmpty() shouldBe false
            someEncrypted.isEmpty() shouldBe false
        }
    }
    }
}
