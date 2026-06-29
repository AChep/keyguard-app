package app.keemobile.kotpass.xml

import app.keemobile.kotpass.common.renderTestXmlString
import app.keemobile.kotpass.constants.Const
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldNotBeZero
import app.keemobile.kotpass.common.matchers.shouldBe
import okio.ByteString.Companion.toByteString
import org.redundent.kotlin.xml.parse

class CustomIconsSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("CustomIcons XML") {
        it("Deserialize XML") {
            val pngSignature = Const.bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val document = ClassLoader
                .getSystemResourceAsStream("xml/custom_icons.xml")!!
                .use(::parse)
            val icons = CustomIcons.unmarshal(document)

            icons.size.shouldNotBeZero()

            val slice = icons.values
                .first()
                .data
                .toByteString(0, pngSignature.size)
            slice shouldBe pngSignature
        }

        it("Serialize XML") {
            val context = XmlContext.Encode.Plain(
                version = FormatVersion(4, 1),
                binaries = linkedMapOf(),
                memoryProtectionFlags = emptySet()
            )
            val resourceStream = { ClassLoader.getSystemResourceAsStream("xml/custom_icons.xml")!! }
            val document = resourceStream().use(::parse)
            val rawData = resourceStream().readAllBytes().decodeToString()
            val icons = CustomIcons.unmarshal(document)

            renderTestXmlString(CustomIcons.marshal(context, icons)) shouldBe rawData
        }
    }
    }
}
