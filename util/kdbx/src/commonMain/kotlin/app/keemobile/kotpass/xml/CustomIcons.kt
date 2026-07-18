package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.extensions.addDateTime
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.readBytesOrNull
import app.keemobile.kotpass.extensions.readUuidOrNull
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml.Tags
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object CustomIcons {
    fun unmarshal(
        reader: XmlReader,
        innerEncryption: EncryptionSaltGenerator,
    ): Map<Uuid, CustomIcon> {
        val icons = mutableListOf<Pair<Uuid, CustomIcon>>()
        reader.forEachChildElement {
            if (reader.isUnqualifiedElement(Tags.Meta.CustomIcons.Item)) {
                unmarshalCustomIcon(reader, innerEncryption)?.let(icons::add)
            } else {
                reader.discardKdbxElement(innerEncryption)
            }
        }
        return icons.toMap()
    }

    private fun unmarshalCustomIcon(
        reader: XmlReader,
        innerEncryption: EncryptionSaltGenerator,
    ): Pair<Uuid, CustomIcon>? {
        var id: Uuid? = null
        var data: ByteArray? = null
        var name: String? = null
        var lastModified: Instant? = null

        reader.forEachChildElement {
            when {
                reader.isUnqualifiedElement(Tags.Meta.CustomIcons.ItemUuid) ->
                    id = reader.readUuidOrNull()
                reader.isUnqualifiedElement(Tags.Meta.CustomIcons.ItemData) ->
                    data = reader.readBytesOrNull()
                reader.isUnqualifiedElement(Tags.Meta.CustomIcons.ItemName) ->
                    name = reader.readElementTextOrNull()
                reader.isUnqualifiedElement(Tags.TimeData.LastModificationTime) ->
                    lastModified = reader.readInstantOrNull()
                else -> reader.discardKdbxElement(innerEncryption)
            }
        }
        val iconId = id ?: return null
        val iconData = data ?: return null
        return iconId to CustomIcon(iconData, name, lastModified)
    }

    fun marshalTo(
        context: XmlContext.Encode,
        customIcons: Map<Uuid, CustomIcon>,
        writer: XmlWriter
    ) = writer.element(Tags.Meta.CustomIcons.TagName) {
        for ((key, item) in customIcons) {
            element(Tags.Meta.CustomIcons.Item) {
                element(Tags.Meta.CustomIcons.ItemUuid) { addUuid(key) }
                element(Tags.Meta.CustomIcons.ItemData) { addBytes(item.data) }

                if (context.version.isAtLeast(4, 1)) {
                    element(Tags.Meta.CustomIcons.ItemName) {
                        if (item.name != null) verbatimText(item.name)
                    }
                    element(Tags.TimeData.LastModificationTime) {
                        addDateTime(context, item.lastModified)
                    }
                }
            }
        }
    }
}
