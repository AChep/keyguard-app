package app.keemobile.kotpass.models

import app.keemobile.kotpass.extensions.parseAsXml
import app.keemobile.kotpass.resources.DeletedObjectRes
import app.keemobile.kotpass.xml.marshal
import app.keemobile.kotpass.xml.unmarshalDeletedObject
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldNotBe
import org.redundent.kotlin.xml.PrintOptions

class DeletedObjectSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("DeletedObject") {
        it("Parsing from Xml string") {
            val root = DeletedObjectRes
                .BasicXml
                .parseAsXml()
            val deletedObject = unmarshalDeletedObject(root)

            deletedObject shouldBe DeletedObjectRes.BasicObject
        }

        it("Uuid is encoded as Base64") {
            val context = XmlContext.Encode.Plain(
                version = FormatVersion(4, 0),
                binaries = linkedMapOf(),
                memoryProtectionFlags = emptySet()
            )

            DeletedObjectRes
                .BasicObject
                .marshal(context)
                .toString(PrintOptions(singleLineTextElements = true))
                .indexOf(DeletedObjectRes.Base64StringUuid) shouldNotBe -1
        }
    }
    }
}
