package app.keemobile.kotpass.xml

import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldNotBeZero
import app.keemobile.kotpass.common.readResourceText
import app.keemobile.kotpass.common.renderTestXmlString
import app.keemobile.kotpass.common.runKotpassSpec
import app.keemobile.kotpass.constants.Const
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.parseAsXmlReader
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import okio.ByteString.Companion.toByteString
import kotlin.test.Test

class CustomIconsSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("CustomIcons XML") {
        it("Deserialize XML") {
            val pngSignature = Const.bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val document = readResourceText("xml/custom_icons.xml")
                .parseAsXmlReader()
            val icons = CustomIcons.unmarshal(
                document,
                EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
            )

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
            val rawData = readResourceText("xml/custom_icons.xml")
            val document = rawData.parseAsXmlReader()
            val icons = CustomIcons.unmarshal(
                document,
                EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
            )

            renderTestXmlString { CustomIcons.marshalTo(context, icons, it) } shouldBe rawData
        }
    }
    }
}
