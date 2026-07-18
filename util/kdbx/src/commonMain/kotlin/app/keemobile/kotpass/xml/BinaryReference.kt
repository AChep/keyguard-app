package app.keemobile.kotpass.xml

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml.Tags
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter

/**
 * Note that references with invalid IDs are skipped.
 * This table represents how other clients handle this situation:
 *
 * | Client    | Behaviour                               |
 * |-----------|-----------------------------------------|
 * | KeePass   | Drops invalid attachments               |
 * | KeePassXC | Keeps attachments as invalid references |
 * | KeeWeb    | Drops invalid attachments               |
 * | MacPass   | Drops invalid attachments               |
 *
 * @return BinaryReference or **null** when reference ID is invalid.
 */
internal fun unmarshalBinaryReference(
    context: XmlContext.Decode,
    reader: XmlReader
): BinaryReference? {
    var name: String? = null
    var ref: Int? = null
    var inlineBinary: BinaryData? = null

    reader.forEachChildElement {
        when {
            reader.isUnqualifiedElement(Tags.Entry.BinaryReferences.ItemKey) -> {
                name = reader.readElementTextOrNull()
            }
            reader.isUnqualifiedElement(Tags.Entry.BinaryReferences.ItemValue) -> {
                ref = reader.intAttributeOrNull(FormatXml.Attributes.Ref)
                if (ref != null) {
                    reader.discardKdbxElement(context.encryption)
                } else {
                    val markers = reader.readProtectedXmlValueMarkers()
                    val compressed = reader.booleanAttributeOrNull(
                        FormatXml.Attributes.Compressed,
                    ) ?: false
                    val encoded = decodeBase64ValueOrNull(
                        reader.readElementTextOrNull(),
                        Tags.Entry.BinaryReferences.ItemValue,
                    ) ?: ByteArray(0)
                    val content = if (markers.usesInnerEncryption) {
                        try {
                            context.encryption.processBytes(encoded)
                        } finally {
                            encoded.fill(0)
                        }
                    } else {
                        encoded
                    }
                    inlineBinary = if (compressed) {
                        BinaryData.Compressed(markers.isProtected, content)
                    } else {
                        BinaryData.Uncompressed(markers.isProtected, content)
                    }
                }
            }
            else -> reader.discardKdbxElement(context.encryption)
        }
    }
    val hash = inlineBinary?.let(context::addBinary) ?: run {
        val id = ref ?: throw FormatError.InvalidXml("Invalid binary reference id.")
        context.binaryIndex.hashByRef(id) ?: return null
    }

    return BinaryReference(
        hash = hash,
        name = name
            ?: throw FormatError.InvalidXml("Invalid binary reference key.")
    )
}

internal fun BinaryReference.marshalTo(
    context: XmlContext.Encode,
    writer: XmlWriter
) {
    val id = context.binaryWritePlan.refByHash(hash)
    if (id == null) {
        throw FormatError.InvalidContent("No binary with hash: ${hash.hex()}.")
    }

    writer.element(Tags.Entry.BinaryReferences.TagName) {
        element(Tags.Entry.BinaryReferences.ItemKey) {
            verbatimText(name)
        }
        element(Tags.Entry.BinaryReferences.ItemValue) {
            attribute(FormatXml.Attributes.Ref, id.toString())
        }
    }
}
