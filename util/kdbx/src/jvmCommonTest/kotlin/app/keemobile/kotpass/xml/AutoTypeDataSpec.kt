package app.keemobile.kotpass.xml

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldNotBeZero
import app.keemobile.kotpass.common.readResourceText
import app.keemobile.kotpass.common.renderTestXmlString
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.constants.AutoTypeObfuscation
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.parseAsXmlReader
import kotlin.test.Test

class AutoTypeDataSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("AutoType Data") {
        it("Deserialize XML") {
            val document = readResourceText("xml/autotype.xml")
                .parseAsXmlReader()
            val autoTypeData = unmarshalAutoTypeData(
                document,
                EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
            )

            autoTypeData.enabled shouldBe true
            autoTypeData.obfuscation shouldBe AutoTypeObfuscation.UseClipboard
            autoTypeData.items.size.shouldNotBeZero()
        }

        it("Serialize XML") {
            val rawData = readResourceText("xml/autotype.xml")
            val document = rawData.parseAsXmlReader()
            val autoTypeData = unmarshalAutoTypeData(
                document,
                EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
            )

            renderTestXmlString { autoTypeData.marshalTo(it) } shouldBe rawData
        }
    }
    }
}
