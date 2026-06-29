package app.keemobile.kotpass.xml

import app.keemobile.kotpass.extensions.addDateTime
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.getUuid
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.XmlContext
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.node

internal fun unmarshalDeletedObject(node: Node): DeletedObject? {
    val uuid = node.firstOrNull(FormatXml.Tags.Uuid)?.getUuid()
    val dateTime = node.firstOrNull(FormatXml.Tags.DeletedObjects.Time)?.getInstant()

    return if (uuid != null && dateTime != null) {
        DeletedObject(uuid, dateTime)
    } else {
        null
    }
}

internal fun DeletedObject.marshal(context: XmlContext.Encode): Node {
    return node(FormatXml.Tags.DeletedObjects.Object) {
        element(FormatXml.Tags.Uuid) { addUuid(id) }
        element(FormatXml.Tags.DeletedObjects.Time) { addDateTime(context, deletionTime) }
    }
}
