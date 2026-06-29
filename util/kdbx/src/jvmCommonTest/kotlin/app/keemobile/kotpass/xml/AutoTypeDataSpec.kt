package app.keemobile.kotpass.xml

import app.keemobile.kotpass.common.renderTestXmlString
import app.keemobile.kotpass.constants.AutoTypeObfuscation
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldNotBeZero
import app.keemobile.kotpass.common.matchers.shouldBe
import org.redundent.kotlin.xml.parse

class AutoTypeDataSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("AutoType Data") {
        it("Deserialize XML") {
            val document = ClassLoader
                .getSystemResourceAsStream("xml/autotype.xml")!!
                .use(::parse)
            val autoTypeData = unmarshalAutoTypeData(document)

            autoTypeData.enabled shouldBe true
            autoTypeData.obfuscation shouldBe AutoTypeObfuscation.UseClipboard
            autoTypeData.items.size.shouldNotBeZero()
        }

        it("Serialize XML") {
            val resourceStream = { ClassLoader.getSystemResourceAsStream("xml/autotype.xml")!! }
            val document = resourceStream().use(::parse)
            val rawData = resourceStream().readAllBytes().decodeToString()
            val autoTypeData = unmarshalAutoTypeData(document)

            renderTestXmlString(autoTypeData.marshal()) shouldBe rawData
        }
    }
    }
}
