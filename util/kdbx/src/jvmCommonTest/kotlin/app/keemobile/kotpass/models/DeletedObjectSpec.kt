package app.keemobile.kotpass.models

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.parseAsXmlReader
import app.keemobile.kotpass.resources.DeletedObjectRes
import app.keemobile.kotpass.common.renderTestXmlString
import app.keemobile.kotpass.xml.marshalTo
import app.keemobile.kotpass.xml.unmarshalDeletedObject
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldNotBe

class DeletedObjectSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("DeletedObject") {
        it("Parsing from Xml string") {
            val root = DeletedObjectRes
                .BasicXml
                .parseAsXmlReader()
            val deletedObject = unmarshalDeletedObject(
                root,
                EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
            )

            deletedObject shouldBe DeletedObjectRes.BasicObject
        }

        it("Uuid is encoded as Base64") {
            val context = XmlContext.Encode.Plain(
                version = FormatVersion(4, 0),
                binaries = linkedMapOf(),
                memoryProtectionFlags = emptySet()
            )

            renderTestXmlString { DeletedObjectRes.BasicObject.marshalTo(context, it) }
                .indexOf(DeletedObjectRes.Base64StringUuid) shouldNotBe -1
        }
    }
    }
}
