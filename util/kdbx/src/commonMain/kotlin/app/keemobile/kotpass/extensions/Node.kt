package app.keemobile.kotpass.extensions

import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.io.decodeBase64ToArray
import app.keemobile.kotpass.io.encodeBase64
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml
import app.keemobile.kotpass.xml.marshal
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.TextElement
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal fun Node.childNodes() = children.filterIsInstance<Node>()

internal fun Node.getText() = (children.firstOrNull() as? TextElement)?.text

internal fun Node.getGroupOverride(): GroupOverride {
    val value = getText()
        ?.lowercase()
        ?.toBooleanStrictOrNull()

    return when (value) {
        null -> GroupOverride.Inherit
        true -> GroupOverride.Enabled
        false -> GroupOverride.Disabled
    }
}

internal fun Node.getUuid(): Uuid? = getText()?.let { text ->
    Uuid.fromByteArray(text.decodeBase64ToArray())
}

internal fun Node.getBytes(): ByteArray? {
    return getText()?.decodeBase64ToArray()
}

internal fun Node.addDateTime(
    context: XmlContext.Encode,
    instant: Instant?
) {
    if (instant != null) {
        text(instant.marshal(context))
    }
}

internal fun Node.addBoolean(value: Boolean) {
    text(value.toXmlString())
}

internal fun Node.addGroupOverride(value: GroupOverride) {
    text(
        when (value) {
            GroupOverride.Inherit -> FormatXml.Values.Null
            GroupOverride.Enabled -> FormatXml.Values.True
            GroupOverride.Disabled -> FormatXml.Values.False
        }
    )
}

internal fun Node.addUuid(value: Uuid) {
    text(value.toByteArray().encodeBase64())
}

internal fun Node.addBytes(bytes: ByteArray) {
    text(bytes.encodeBase64())
}
