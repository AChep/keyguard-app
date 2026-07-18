package app.keemobile.kotpass.xml

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldNotBeEmpty
import app.keemobile.kotpass.common.readResourceText
import app.keemobile.kotpass.common.renderTestXmlString
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.parseAsXmlReader
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import kotlin.test.Test

class GroupSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Group XML") {
        it("Deserialize XML") {
            val context = XmlContext.Decode(
                version = FormatVersion(4, 1),
                encryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
                binaries = linkedMapOf()
            )
            val document = readResourceText("xml/group.xml")
                .parseAsXmlReader()
            val group = unmarshalGroup(context, document)

            group.groups.shouldNotBeEmpty()
            group.groups.first().groups.shouldNotBeEmpty()
        }

        it("Serialize XML") {
            val version = FormatVersion(4, 1)
            val encryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf())
            val encodeCtx = XmlContext.Encode.Plain(version, linkedMapOf(), emptySet())
            val decodeCtx = XmlContext.Decode(version, encryption, linkedMapOf())
            val rawData = readResourceText("xml/group.xml")
            val document = rawData.parseAsXmlReader()
            val group = unmarshalGroup(decodeCtx, document)

            renderTestXmlString { group.marshalTo(encodeCtx, it) } shouldBe rawData
        }
    }
    }
}
