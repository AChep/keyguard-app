package app.keemobile.kotpass.xml

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.readResourceText
import app.keemobile.kotpass.common.renderTestXmlString
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.parseAsXmlReader
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import kotlin.test.Test

class MetaSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Meta XML") {
        it("Deserialize XML") {
            val document = readResourceText("xml/meta.xml")
                .parseAsXmlReader()
            val meta = unmarshalMeta(
                document,
                EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
            )

            meta.generator shouldBe "None"
            meta.maintenanceHistoryDays shouldBe 365U
        }

        it("Serialize XML") {
            val rawData = readResourceText("xml/meta.xml")
            val document = rawData.parseAsXmlReader()
            val meta = unmarshalMeta(
                document,
                EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
            )
            val context = XmlContext.Encode.Plain(
                version = FormatVersion(4, 1),
                binaries = linkedMapOf(),
                memoryProtectionFlags = meta.memoryProtection
            )

            renderTestXmlString { meta.marshalTo(context, it) } shouldBe rawData
        }
    }
    }
}
