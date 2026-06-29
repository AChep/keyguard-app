package app.keemobile.kotpass.models

import app.keemobile.kotpass.constants.MemoryProtectionFlag
import app.keemobile.kotpass.extensions.parseAsXml
import app.keemobile.kotpass.resources.MetaRes
import app.keemobile.kotpass.resources.TimeDataRes
import app.keemobile.kotpass.xml.unmarshalMeta
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldBeInstanceOf

class MetaSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Metadata") {
        it("Properly deserialized from Xml") {
            val meta = unmarshalMeta(MetaRes.BasicXml.parseAsXml())

            meta.generator shouldBe MetaRes.DummyText
            meta.description shouldBe MetaRes.DummyText
            meta.nameChanged shouldBe TimeDataRes.ParsedDateTime
            meta.memoryProtection.containsAll(MemoryProtectionFlag.entries) shouldBe true
            meta.recycleBinEnabled shouldBe false
            meta.binaries.values.first().shouldBeInstanceOf<BinaryData.Uncompressed>()
            meta.binaries.values.first().getContent() shouldBe MetaRes.DummyText.toByteArray()
        }
    }
    }
}
