package app.keemobile.kotpass.xml

import app.keemobile.kotpass.extensions.addDateTime
import app.keemobile.kotpass.extensions.childNodes
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.XmlContext
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.node

internal object CustomData {
    fun unmarshal(node: Node): Map<String, CustomDataValue> {
        return node
            .childNodes()
            .filter { it.nodeName == FormatXml.Tags.CustomData.Item }
            .mapNotNull(::unmarshalCustomDataItem)
            .toMap()
    }

    private fun unmarshalCustomDataItem(node: Node): Pair<String, CustomDataValue>? {
        val key = node
            .firstOrNull(FormatXml.Tags.CustomData.ItemKey)
            ?.getText()
            ?: return null
        val value = node
            .firstOrNull(FormatXml.Tags.CustomData.ItemValue)
            ?.getText()
            ?: return null
        val lastModified = node
            .firstOrNull(FormatXml.Tags.TimeData.LastModificationTime)
            ?.getInstant()

        return key to CustomDataValue(value, lastModified)
    }

    fun marshal(
        context: XmlContext.Encode,
        customData: Map<String, CustomDataValue>
    ): Node = node(FormatXml.Tags.CustomData.TagName) {
        for ((key, item) in customData) {
            element(FormatXml.Tags.CustomData.Item) {
                element(FormatXml.Tags.CustomData.ItemKey) { text(key) }
                element(FormatXml.Tags.CustomData.ItemValue) { text(item.value) }

                if (context.version.isAtLeast(4, 1)) {
                    element(FormatXml.Tags.TimeData.LastModificationTime) {
                        addDateTime(context, item.lastModified)
                    }
                }
            }
        }
    }
}
