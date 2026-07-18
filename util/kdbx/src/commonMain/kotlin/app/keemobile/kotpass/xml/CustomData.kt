package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.addDateTime
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.XmlContext
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import kotlin.time.Instant

internal object CustomData {
    fun unmarshal(
        reader: XmlReader,
        innerEncryption: EncryptionSaltGenerator,
    ): Map<String, CustomDataValue> {
        val items = mutableListOf<Pair<String, CustomDataValue>>()
        reader.forEachChildElement {
            if (reader.isUnqualifiedElement(FormatXml.Tags.CustomData.Item)) {
                unmarshalCustomDataItem(reader, innerEncryption)?.let(items::add)
            } else {
                reader.discardKdbxElement(innerEncryption)
            }
        }
        return items.toMap()
    }

    private fun unmarshalCustomDataItem(
        reader: XmlReader,
        innerEncryption: EncryptionSaltGenerator,
    ): Pair<String, CustomDataValue>? {
        var key: String? = null
        var value: String? = null
        var lastModified: Instant? = null

        reader.forEachChildElement {
            when {
                reader.isUnqualifiedElement(FormatXml.Tags.CustomData.ItemKey) ->
                    key = reader.readElementTextOrNull()
                reader.isUnqualifiedElement(FormatXml.Tags.CustomData.ItemValue) ->
                    value = reader.readElementTextOrNull()
                reader.isUnqualifiedElement(FormatXml.Tags.TimeData.LastModificationTime) ->
                    lastModified = reader.readInstantOrNull()
                else -> reader.discardKdbxElement(innerEncryption)
            }
        }
        val itemKey = key ?: return null
        val itemValue = value ?: return null
        return itemKey to CustomDataValue(itemValue, lastModified)
    }

    fun marshalTo(
        context: XmlContext.Encode,
        customData: Map<String, CustomDataValue>,
        writer: XmlWriter
    ) = writer.element(FormatXml.Tags.CustomData.TagName) {
        for ((key, item) in customData) {
            element(FormatXml.Tags.CustomData.Item) {
                element(FormatXml.Tags.CustomData.ItemKey) { verbatimText(key) }
                element(FormatXml.Tags.CustomData.ItemValue) { verbatimText(item.value) }

                if (context.version.isAtLeast(4, 1)) {
                    element(FormatXml.Tags.TimeData.LastModificationTime) {
                        addDateTime(context, item.lastModified)
                    }
                }
            }
        }
    }
}
