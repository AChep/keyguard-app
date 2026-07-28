package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.XmlAttribute
import app.keemobile.kotpass.models.XmlContext
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter

/** KDBX protection markers attached to an XML value element. */
internal data class XmlProtectedValueMarkers(
    val usesInnerEncryption: Boolean,
    val protectsInMemory: Boolean,
) {
    val isProtected: Boolean
        get() = usesInnerEncryption || protectsInMemory
}

/**
 * Reads namespace-less KDBX protection markers from the current start element.
 *
 * A namespaced extension attribute with the same local name is ordinary extension data and must
 * not affect KDBX value decoding.
 */
internal fun XmlReader.readProtectedXmlValueMarkers(): XmlProtectedValueMarkers {
    var usesInnerEncryption = false
    var protectsInMemory = false
    for (index in 0 until attributeCount) {
        if (getAttributeNamespace(index).isNotEmpty()) continue
        when (val name = getAttributeLocalName(index)) {
            FormatXml.Attributes.Protected -> {
                usesInnerEncryption = getAttributeValue(index).parseProtectionMarker(name)
            }
            FormatXml.Attributes.ProtectedInMemPlainXml -> {
                protectsInMemory = getAttributeValue(index).parseProtectionMarker(name)
            }
        }
    }
    return XmlProtectedValueMarkers(
        usesInnerEncryption = usesInnerEncryption,
        protectsInMemory = protectsInMemory,
    )
}

/** Returns whether this is a namespace-less KDBX protection control attribute. */
internal fun XmlAttribute.isProtectedXmlValueMarker(): Boolean =
    name.namespaceUri.isEmpty() &&
        name.localName in PROTECTED_XML_VALUE_ATTRIBUTE_NAMES

/**
 * Decodes XML value text according to [markers]. Inner encryption takes precedence when both
 * markers are present, matching the historical field parser behavior.
 */
internal fun decodeProtectedXmlValue(
    text: String?,
    markers: XmlProtectedValueMarkers,
    innerEncryption: EncryptionSaltGenerator?,
    elementName: String,
): EntryValue = when {
    markers.usesInnerEncryption -> {
        val encryption = innerEncryption ?: throw FormatError.InvalidXml(
            "Protected element '$elementName' has no encryption context."
        )
        val bytes = decodeBase64ValueOrNull(text, elementName) ?: ByteArray(0)
        val salt = try {
            encryption.getSalt(bytes.size)
        } catch (error: Throwable) {
            bytes.fill(0)
            throw error
        }
        EntryValue.Encrypted(EncryptedValue(bytes, salt))
    }
    markers.protectsInMemory ->
        EntryValue.Encrypted(EncryptedValue.fromString(text ?: ""))
    else -> EntryValue.Plain(text ?: "")
}

/** Reads and decodes the protected value element at the reader's current position. */
internal fun XmlReader.readProtectedXmlValue(
    innerEncryption: EncryptionSaltGenerator?,
): EntryValue {
    val elementName = localName
    val markers = readProtectedXmlValueMarkers()
    return decodeProtectedXmlValue(
        text = readElementTextOrNull(),
        markers = markers,
        innerEncryption = innerEncryption,
        elementName = elementName,
    )
}

/**
 * Discards the current KDBX element while preserving the inner-cipher stream position.
 *
 * KeePass advances the shared inner stream for protected values even when their containing XML is
 * unknown and discarded. This iterative traversal mirrors that behavior without retaining either
 * the decoded ciphertext or the generated salt.
 */
internal fun XmlReader.discardKdbxElement(
    innerEncryption: EncryptionSaltGenerator,
) {
    val rootMarkers = readProtectedXmlValueMarkers()
    if (rootMarkers.isProtected) {
        discardProtectedXmlValue(rootMarkers, innerEncryption)
        return
    }

    var depth = 1
    while (depth > 0) {
        when (next()) {
            EventType.START_ELEMENT -> {
                val markers = readProtectedXmlValueMarkers()
                if (markers.isProtected) {
                    discardProtectedXmlValue(markers, innerEncryption)
                } else {
                    depth++
                }
            }
            EventType.END_ELEMENT -> depth--
            EventType.END_DOCUMENT ->
                throw FormatError.InvalidXml("Unexpected end of document.")
            else -> Unit
        }
    }
}

private fun XmlReader.discardProtectedXmlValue(
    markers: XmlProtectedValueMarkers,
    innerEncryption: EncryptionSaltGenerator,
) {
    val elementName = localName
    val text = readElementTextOrNull()
    if (!markers.usesInnerEncryption) return

    val ciphertext = decodeBase64ValueOrNull(text, elementName) ?: ByteArray(0)
    try {
        val salt = innerEncryption.getSalt(ciphertext.size)
        salt.fill(0)
    } finally {
        ciphertext.fill(0)
    }
}

/**
 * Writes [value] using the canonical namespace-less KDBX protection marker for [context].
 *
 * Inner encryption is advanced exactly when this function is invoked. Callers must therefore
 * invoke it in document order and must not precompute protected values.
 */
internal fun XmlWriter.writeProtectedXmlValue(
    context: XmlContext.Encode,
    value: EntryValue,
    protectInMemory: Boolean = value is EntryValue.Encrypted,
) {
    when (context) {
        is XmlContext.Encode.Encrypted -> {
            if (value is EntryValue.Encrypted) {
                attribute(
                    FormatXml.Attributes.Protected,
                    FormatXml.Values.True,
                )
                val plaintext = value.content.encodeToByteArray()
                val encrypted = try {
                    context.innerEncryption.processBytes(plaintext)
                } catch (error: Throwable) {
                    plaintext.fill(0)
                    throw error
                }
                try {
                    addBytes(encrypted)
                } finally {
                    plaintext.fill(0)
                    if (encrypted !== plaintext) encrypted.fill(0)
                }
            } else {
                verbatimText(value.content)
            }
        }
        is XmlContext.Encode.Plain -> {
            if (protectInMemory) {
                attribute(
                    FormatXml.Attributes.ProtectedInMemPlainXml,
                    FormatXml.Values.True,
                )
            }
            verbatimText(value.content)
        }
    }
}

private fun String.parseProtectionMarker(name: String): Boolean {
    val value = trim().takeIf(String::isNotEmpty) ?: return false
    return value.lowercase().toBooleanStrictOrNull()
        ?: throw FormatError.InvalidXml(
            "Element '@$name' contains an invalid boolean value '$value'."
        )
}

private val PROTECTED_XML_VALUE_ATTRIBUTE_NAMES = setOf(
    FormatXml.Attributes.Protected,
    FormatXml.Attributes.ProtectedInMemPlainXml,
)
