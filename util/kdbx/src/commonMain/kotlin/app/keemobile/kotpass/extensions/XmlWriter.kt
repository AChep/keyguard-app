package app.keemobile.kotpass.extensions

import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.io.encodeBase64
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml
import app.keemobile.kotpass.xml.marshal
import nl.adaptivity.xmlutil.XmlWriter
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Typed writers for KDBX value elements, writing the text content
 * of the element currently open on the [XmlWriter].
 */

internal fun XmlWriter.addDateTime(
    context: XmlContext.Encode,
    instant: Instant?
) {
    if (instant != null) {
        text(instant.marshal(context))
    }
}

internal fun XmlWriter.addBoolean(value: Boolean) {
    text(value.toXmlString())
}

internal fun XmlWriter.addGroupOverride(value: GroupOverride) {
    text(
        when (value) {
            GroupOverride.Inherit -> FormatXml.Values.Null
            GroupOverride.Enabled -> FormatXml.Values.True
            GroupOverride.Disabled -> FormatXml.Values.False
        }
    )
}

internal fun XmlWriter.addUuid(value: Uuid) {
    text(value.toByteArray().encodeBase64())
}

internal fun XmlWriter.addBytes(bytes: ByteArray) {
    text(bytes.encodeBase64())
}
