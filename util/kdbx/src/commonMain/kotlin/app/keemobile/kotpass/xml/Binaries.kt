package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.database.BinaryPool
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.extensions.readBytesOrNull
import app.keemobile.kotpass.extensions.toXmlString
import app.keemobile.kotpass.models.BinaryData
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import okio.ByteString

/**
 * Note: memory protection applies only to binaries stored in inner header (KDBX 4.x)
 */
internal fun unmarshalBinaries(
    reader: XmlReader,
    innerEncryption: EncryptionSaltGenerator,
): Map<ByteString, BinaryData> {
    val binaries = BinaryPool()
    reader.forEachChildElement {
        if (reader.isUnqualifiedElement(FormatXml.Tags.Meta.Binaries.Item)) {
            val (ref, binary) = unmarshalBinaryData(reader)
            binaries.add(ref, binary)
        } else {
            reader.discardKdbxElement(innerEncryption)
        }
    }
    return binaries
}

private fun unmarshalBinaryData(reader: XmlReader): Pair<Int, BinaryData> {
    val id = reader.intAttributeOrNull(FormatXml.Attributes.Id)
        ?: throw FormatError.InvalidXml("Binary node has no id.")
    val compressed = reader.booleanAttributeOrNull(FormatXml.Attributes.Compressed) ?: false
    val bytes = reader.readBytesOrNull() ?: ByteArray(0)
    val binary = when {
        compressed -> BinaryData.Compressed(false, bytes)
        else -> BinaryData.Uncompressed(false, bytes)
    }

    return id to binary
}

internal fun BinaryData.marshalTo(id: Int, writer: XmlWriter) {
    val compressed = this is BinaryData.Compressed
    writer.element(FormatXml.Tags.Meta.Binaries.Item) {
        attribute(FormatXml.Attributes.Id, id.toString())
        attribute(FormatXml.Attributes.Compressed, compressed.toXmlString())
        addBytes(rawContent)
    }
}
