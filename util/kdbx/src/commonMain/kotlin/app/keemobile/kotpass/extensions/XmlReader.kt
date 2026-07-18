package app.keemobile.kotpass.extensions

import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.xml.readBase64BytesOrNull
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.xml.readTrimmedElementTextOrNull
import app.keemobile.kotpass.xml.readUuidScalarOrNull
import nl.adaptivity.xmlutil.XmlReader
import kotlin.uuid.Uuid

/**
 * Typed readers for KDBX value elements. Each one consumes the element
 * the reader is positioned at through its end tag. Values are trimmed
 * before decoding so that formatting whitespace around binary or
 * numeric payloads is tolerated the way the previous parser did.
 */

internal fun XmlReader.readGroupOverride(): GroupOverride {
    val name = localName
    return when (val value = readTrimmedElementTextOrNull()?.lowercase()) {
        null, "null" -> GroupOverride.Inherit
        "true" -> GroupOverride.Enabled
        "false" -> GroupOverride.Disabled
        else -> throw FormatError.InvalidXml(
            "Element '$name' contains an invalid group override '$value'."
        )
    }
}

internal fun XmlReader.readUuidOrNull(): Uuid? = readUuidScalarOrNull()

internal fun XmlReader.readBytesOrNull(): ByteArray? = readBase64BytesOrNull()
