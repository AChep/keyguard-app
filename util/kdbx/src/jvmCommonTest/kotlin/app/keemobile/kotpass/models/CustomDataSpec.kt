package app.keemobile.kotpass.models

import app.keemobile.kotpass.extensions.parseAsXml
import app.keemobile.kotpass.resources.CustomDataRes
import app.keemobile.kotpass.xml.CustomData
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldNotBe
import org.redundent.kotlin.xml.PrintOptions

class CustomDataSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Parsing CustomData from Xml string") {
        it("Basic custom data") {
            val customData = CustomData
                .unmarshal(CustomDataRes.BasicXml.parseAsXml())

            customData["k1"] shouldBe CustomDataValue("v1")
            customData["k2"] shouldBe CustomDataValue("v2")
        }

        it("Empty custom data") {
            CustomData
                .unmarshal(CustomDataRes.EmptyTagXml.parseAsXml())
                .isEmpty() shouldBe true
        }

        it("Skips unknown tags") {
            val customData = CustomData
                .unmarshal(CustomDataRes.UnknownTagsXml.parseAsXml())

            customData.size shouldBe 1
            customData["k1"] shouldBe CustomDataValue("v1")
        }

        it("Skips empty keys") {
            CustomData
                .unmarshal(CustomDataRes.EmptyKeysXml.parseAsXml())
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

            CustomData.marshal(context, customData)
                .toString(PrintOptions(singleLineTextElements = true))
                .indexOf("<Key>k1</Key>") shouldNotBe -1
        }
    }
    }
}
