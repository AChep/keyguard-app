package app.keemobile.kotpass.models

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.parseAsXml
import app.keemobile.kotpass.resources.TimeDataRes
import app.keemobile.kotpass.xml.marshal
import app.keemobile.kotpass.xml.unmarshalTimeData
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldNotBe
import org.redundent.kotlin.xml.PrintOptions

class TimeDataSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Parsing DateTime from Xml string") {
        it("Date time in ISO text format") {
            val root = TimeDataRes
                .getBaseXml(TimeDataRes.DateTimeText)
                .parseAsXml()
            val times = unmarshalTimeData(root)

            times.creationTime shouldBe TimeDataRes.ParsedDateTime
            times.lastAccessTime shouldBe TimeDataRes.ParsedDateTime
            times.expiryTime shouldBe null
        }

        it("Date time in binary timestamp") {
            val root = TimeDataRes
                .getBaseXml(TimeDataRes.Base64BinaryDateTimeText)
                .parseAsXml()
            val times = unmarshalTimeData(root)

            times.creationTime?.toString() shouldBe TimeDataRes.DateTimeText
            times.lastAccessTime?.toString() shouldBe TimeDataRes.DateTimeText
            times.expiryTime shouldBe null
        }
    }

    describe("Writing DateTime to Xml string") {
        it("Using text format") {
            val context = XmlContext.Encode.Plain(
                version = FormatVersion(3, 1),
                binaries = linkedMapOf(),
                memoryProtectionFlags = emptySet()
            )
            val times = TimeData(
                creationTime = TimeDataRes.ParsedDateTime,
                lastAccessTime = TimeDataRes.ParsedDateTime,
                lastModificationTime = TimeDataRes.ParsedDateTime,
                locationChanged = TimeDataRes.ParsedDateTime,
                expiryTime = TimeDataRes.ParsedDateTime
            )

            times.marshal(context)
                .toString(PrintOptions(singleLineTextElements = true))
                .indexOf(TimeDataRes.DateTimeText) shouldNotBe -1
        }

        it("Using binary format") {
            val context = XmlContext.Encode.Encrypted(
                version = FormatVersion(4, 0),
                binaries = linkedMapOf(),
                innerEncryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf())
            )
            val times = TimeData(
                creationTime = TimeDataRes.ParsedDateTime,
                lastAccessTime = TimeDataRes.ParsedDateTime,
                lastModificationTime = TimeDataRes.ParsedDateTime,
                locationChanged = TimeDataRes.ParsedDateTime,
                expiryTime = TimeDataRes.ParsedDateTime
            )

            times.marshal(context)
                .toString(PrintOptions(singleLineTextElements = true))
                .indexOf(TimeDataRes.Base64BinaryDateTimeText) shouldNotBe -1
        }
    }
    }
}
