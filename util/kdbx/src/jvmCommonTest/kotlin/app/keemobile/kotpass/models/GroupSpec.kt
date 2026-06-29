package app.keemobile.kotpass.models

import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.parseAsXml
import app.keemobile.kotpass.resources.GroupRes
import app.keemobile.kotpass.xml.unmarshalGroup
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import app.keemobile.kotpass.common.matchers.shouldNotBe

class GroupSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("Group") {
        it("Properly deserialized from Xml") {
            val context = XmlContext.Decode(
                version = FormatVersion(4, 1),
                encryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
                binaries = linkedMapOf()
            )
            val group = unmarshalGroup(context, GroupRes.BasicXml.parseAsXml())

            group.name shouldBe "Lorem"
            group.icon shouldBe PredefinedIcon.Folder
            group.enableAutoType shouldBe GroupOverride.Inherit
            group.enableSearching shouldBe GroupOverride.Inherit
            group.lastTopVisibleEntry shouldNotBe null
            group.groups.size shouldBe 1
            group.groups.first().name shouldBe "Ipsum"
            group.entries.size shouldBe 1
        }

        it("Finds child Group") {
            val context = XmlContext.Decode(
                version = FormatVersion(4, 1),
                encryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
                binaries = linkedMapOf()
            )
            val group = unmarshalGroup(context, GroupRes.BasicXml.parseAsXml())

            group.findChildGroup { it.name == "Ipsum" } shouldNotBe null
        }

        it("Finds child Entry") {
            val context = XmlContext.Decode(
                version = FormatVersion(4, 1),
                encryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf()),
                binaries = linkedMapOf()
            )
            val group = unmarshalGroup(context, GroupRes.BasicXml.parseAsXml())

            group.findChildEntry {
                it.fields.title?.content == "Lorem"
            } shouldNotBe null
        }
    }
    }
}
