package app.keemobile.kotpass.xml

import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.extensions.addDateTime
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.childNodes
import app.keemobile.kotpass.extensions.getBytes
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.extensions.getUuid
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml.Tags
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.node
import kotlin.uuid.Uuid

internal object CustomIcons {
    fun unmarshal(node: Node): Map<Uuid, CustomIcon> {
        return node
            .childNodes()
            .filter { it.nodeName == Tags.Meta.CustomIcons.Item }
            .mapNotNull(::unmarshalCustomIcon)
            .toMap()
    }

    private fun unmarshalCustomIcon(node: Node): Pair<Uuid, CustomIcon>? {
        val id = node
            .firstOrNull(Tags.Meta.CustomIcons.ItemUuid)
            ?.getUuid()
            ?: return null
        val data = node
            .firstOrNull(Tags.Meta.CustomIcons.ItemData)
            ?.getBytes()
            ?: return null
        val name = node
            .firstOrNull(Tags.Meta.CustomIcons.ItemName)
            ?.getText()
        val lastModified = node
            .firstOrNull(Tags.TimeData.LastModificationTime)
            ?.getInstant()

        return id to CustomIcon(data, name, lastModified)
    }

    fun marshal(
        context: XmlContext.Encode,
        customIcons: Map<Uuid, CustomIcon>
    ): Node = node(Tags.Meta.CustomIcons.TagName) {
        for ((key, item) in customIcons) {
            element(Tags.Meta.CustomIcons.Item) {
                element(Tags.Meta.CustomIcons.ItemUuid) { addUuid(key) }
                element(Tags.Meta.CustomIcons.ItemData) { addBytes(item.data) }

                if (context.version.isAtLeast(4, 1)) {
                    element(Tags.Meta.CustomIcons.ItemName) {
                        if (item.name != null) text(item.name)
                    }
                    element(Tags.TimeData.LastModificationTime) {
                        addDateTime(context, item.lastModified)
                    }
                }
            }
        }
    }
}
