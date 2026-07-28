package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.KeyTransform
import app.keemobile.kotpass.io.encodeHex
import app.keemobile.kotpass.resources.CredentialsRes
import app.keemobile.kotpass.common.runKotpassSpec
import com.artemchep.keyguard.util.foundation.crypto.sha256
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe

class CredentialsSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Credentials") {
        it("Reads from Xml key file") {
            val (input, output) = CredentialsRes.XmlKeyFileVer1
            val credentials = Credentials.from(input.toByteArray())
            val hex = KeyTransform.compositeKey(credentials).encodeHex()
            hex shouldBe output
        }

        it("Creates Xml key file") {
            val key = ByteArray(32) { 1 }
            val keyfile = Credentials.createKeyfile(key)

            Credentials
                .from(keyfile.toByteArray())
                .key!!
                .getBinary()
                .encodeHex() shouldBe key.encodeHex()
        }

        it("Hashes a 64-byte key file when its contents are not hexadecimal") {
            val keyfile = ByteArray(64) { index -> (index + 0x80).toByte() }
            val expected = sha256(keyfile)

            Credentials
                .from(keyfile.copyOf())
                .key!!
                .getBinary() shouldBe expected
        }
    }
    }
}
