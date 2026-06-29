package app.keemobile.kotpass.xml

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.extensions.childNodes
import app.keemobile.kotpass.extensions.getBytes
import app.keemobile.kotpass.extensions.toXmlString
import app.keemobile.kotpass.models.BinaryData
import okio.ByteString
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.node

/**
 * Note: memory protection applies only to binaries stored in inner header (KDBX 4.x)
 */
internal fun unmarshalBinaries(node: Node): Map<ByteString, BinaryData> {
    return node
        .childNodes()
        .filter { it.nodeName == FormatXml.Tags.Meta.Binaries.Item }
        .map(::unmarshalBinaryData)
        .sortedBy { it.first }
        .associate { (_, binary) -> binary.hash to binary }
}

private fun unmarshalBinaryData(node: Node) = with(node) {
    val id = get<String?>(FormatXml.Attributes.Id)?.toInt()
        ?: throw FormatError.InvalidXml("Binary node has no id.")
    val bytes = getBytes()
        ?: throw FormatError.InvalidXml("Empty body of binary node with id: $id.")
    val compressed = get<String?>(FormatXml.Attributes.Compressed).toBoolean()
    val binary = when {
        compressed -> BinaryData.Compressed(false, bytes)
        else -> BinaryData.Uncompressed(false, bytes)
    }

    id to binary
}

internal fun BinaryData.marshal(id: Int): Node {
    val compressed = this is BinaryData.Compressed
    return node(FormatXml.Tags.Meta.Binaries.Item) {
        attribute(FormatXml.Attributes.Id, id)
        attribute(FormatXml.Attributes.Compressed, compressed.toXmlString())
        addBytes(rawContent)
    }
}
