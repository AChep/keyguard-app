package app.keemobile.kotpass.xml

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.decodeBase64ToArray
import nl.adaptivity.xmlutil.XmlReader
import kotlin.uuid.Uuid

internal fun XmlReader.readTrimmedElementTextOrNull(): String? =
    readElementTextOrNull()?.trim()?.takeIf(String::isNotEmpty)

internal fun XmlReader.readBooleanOrNull(): Boolean? {
    val name = localName
    val value = readTrimmedElementTextOrNull() ?: return null
    return value.lowercase().toBooleanStrictOrNull()
        ?: throw invalidScalar(name, "boolean", value)
}

internal fun XmlReader.readIntOrNull(): Int? {
    val name = localName
    val value = readTrimmedElementTextOrNull() ?: return null
    return value.toIntOrNull() ?: throw invalidScalar(name, "integer", value)
}

internal fun XmlReader.readUIntOrNull(): UInt? {
    val name = localName
    val value = readTrimmedElementTextOrNull() ?: return null
    return value.toUIntOrNull() ?: throw invalidScalar(name, "unsigned integer", value)
}

internal fun XmlReader.readBase64BytesOrNull(): ByteArray? {
    val name = localName
    return decodeBase64ValueOrNull(readElementTextOrNull(), name)
}

internal fun decodeBase64ValueOrNull(value: String?, name: String): ByteArray? {
    val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return try {
        trimmed.decodeBase64ToArray()
    } catch (_: Exception) {
        throw invalidScalar(name, "Base64", trimmed)
    }
}

internal fun XmlReader.readUuidScalarOrNull(): Uuid? {
    val name = localName
    val bytes = readBase64BytesOrNull() ?: return null
    return try {
        Uuid.fromByteArray(bytes)
    } catch (_: Exception) {
        throw invalidScalar(name, "UUID", "${bytes.size} decoded bytes")
    }
}

internal fun XmlReader.booleanAttributeOrNull(name: String): Boolean? {
    val value = attributeOrNull(name)?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return value.lowercase().toBooleanStrictOrNull()
        ?: throw invalidScalar("@$name", "boolean", value)
}

internal fun XmlReader.intAttributeOrNull(name: String): Int? {
    val value = attributeOrNull(name)?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return value.toIntOrNull() ?: throw invalidScalar("@$name", "integer", value)
}

private fun invalidScalar(name: String, type: String, value: String): FormatError.InvalidXml =
    FormatError.InvalidXml("Element '$name' contains an invalid $type value '$value'.")
