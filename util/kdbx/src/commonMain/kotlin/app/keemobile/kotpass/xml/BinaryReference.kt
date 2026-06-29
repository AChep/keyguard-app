package app.keemobile.kotpass.xml

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml.Tags
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.node

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
    node: Node
): BinaryReference? {
    val id = node
        .firstOrNull(Tags.Entry.BinaryReferences.ItemValue)
        ?.get<String?>(FormatXml.Attributes.Ref)
        ?.toInt()
        ?: throw FormatError.InvalidXml("Invalid binary reference id.")
    val hash = context.binaryIndex
        .hashByRef(id)
        ?: return null

    return BinaryReference(
        hash = hash,
        name = node
            .firstOrNull(Tags.Entry.BinaryReferences.ItemKey)
            ?.getText()
            ?: throw FormatError.InvalidXml("Invalid binary reference key.")
    )
}

internal fun BinaryReference.marshal(
    context: XmlContext.Encode
): Node {
    val id = context.binaryIndex.refByHash(hash)
    if (id == null) {
        throw FormatError.InvalidContent("No binary with hash: ${hash.hex()}.")
    }

    return node(Tags.Entry.BinaryReferences.TagName) {
        element(Tags.Entry.BinaryReferences.ItemKey) {
            text(name)
        }
        element(Tags.Entry.BinaryReferences.ItemValue) {
            attribute(FormatXml.Attributes.Ref, id.toString())
        }
    }
}
