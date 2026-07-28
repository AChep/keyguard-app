package app.keemobile.kotpass.xml

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.readResourceText
import app.keemobile.kotpass.common.renderTestXmlString
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.constants.MemoryProtectionFlag
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.parseAsXmlReader
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import kotlin.test.Test

class EntrySpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Entry XML") {
        it("Deserialize XML") {
            val context = XmlContext.Decode(
                version = FormatVersion(4, 1),
                encryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
                binaries = linkedMapOf()
            )
            val document = readResourceText("xml/entry.xml")
                .parseAsXmlReader()
            val entry = unmarshalEntry(context, document)

            entry[BasicField.UserName] shouldBe EntryValue.Plain("Test User")
        }

        it("Serialize XML") {
            val version = FormatVersion(4, 1)
            val encryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf())
            val encodeCtx = XmlContext.Encode.Plain(
                version = version,
                binaries = linkedMapOf(),
                memoryProtectionFlags = setOf(MemoryProtectionFlag.Password)
            )
            val decodeCtx = XmlContext.Decode(version, encryption, linkedMapOf())
            val rawData = readResourceText("xml/entry.xml")
            val document = rawData.parseAsXmlReader()
            val autoTypeData = unmarshalEntry(decodeCtx, document)

            renderTestXmlString { autoTypeData.marshalTo(encodeCtx, it) } shouldBe rawData
        }
    }
    }
}
