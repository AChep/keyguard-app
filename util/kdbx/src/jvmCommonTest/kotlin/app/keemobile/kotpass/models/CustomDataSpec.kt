package app.keemobile.kotpass.models

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.parseAsXmlReader
import app.keemobile.kotpass.resources.CustomDataRes
import app.keemobile.kotpass.xml.CustomData
import app.keemobile.kotpass.common.renderTestXmlString
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldNotBe

class CustomDataSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Parsing CustomData from Xml string") {
        it("Basic custom data") {
            val customData = CustomData
                .unmarshal(
                    CustomDataRes.BasicXml.parseAsXmlReader(),
                    EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
                )

            customData["k1"] shouldBe CustomDataValue("v1")
            customData["k2"] shouldBe CustomDataValue("v2")
        }

        it("Empty custom data") {
            CustomData
                .unmarshal(
                    CustomDataRes.EmptyTagXml.parseAsXmlReader(),
                    EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
                )
                .isEmpty() shouldBe true
        }

        it("Skips unknown tags") {
            val customData = CustomData
                .unmarshal(
                    CustomDataRes.UnknownTagsXml.parseAsXmlReader(),
                    EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
                )

            customData.size shouldBe 1
            customData["k1"] shouldBe CustomDataValue("v1")
        }

        it("Skips empty keys") {
            CustomData
                .unmarshal(
                    CustomDataRes.EmptyKeysXml.parseAsXmlReader(),
                    EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
                )
                .isEmpty() shouldBe true
        }
    }

    describe("Writing CustomData to Xml string") {
        it("Basic custom data") {
            val context = XmlContext.Encode.Plain(
                version = FormatVersion(4, 1),
                binaries = linkedMapOf(),
                memoryProtectionFlags = emptySet()
            )
            val customData = mapOf(
                "k1" to CustomDataValue("v1"),
                "k2" to CustomDataValue("v2")
            )

            renderTestXmlString { CustomData.marshalTo(context, customData, it) }
                .indexOf("<Key>k1</Key>") shouldNotBe -1
        }
    }
    }
}
