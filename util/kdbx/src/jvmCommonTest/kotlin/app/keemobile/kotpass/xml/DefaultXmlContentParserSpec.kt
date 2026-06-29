package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.resources.DefaultXmlContentParserRes
import app.keemobile.kotpass.resources.MetaRes
import app.keemobile.kotpass.resources.TimeDataRes
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe

class DefaultXmlContentParserSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Default Xml content parser") {
        it("Is able to deserialize xml database") {
            val content = DefaultXmlContentParser.unmarshalContent(
                xmlData = DefaultXmlContentParserRes.BasicXml.toByteArray()
            ) {
                XmlContext.Decode(
                    version = FormatVersion(4, 1),
                    encryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
                    binaries = linkedMapOf()
                )
            }
            content.meta.generator shouldBe MetaRes.DummyText
            content.meta.description shouldBe MetaRes.DummyText
            content.meta.nameChanged shouldBe TimeDataRes.ParsedDateTime

            content.group.groups.size shouldBe 1
            content.deletedObjects.size shouldBe 0
        }
    }
    }
}
