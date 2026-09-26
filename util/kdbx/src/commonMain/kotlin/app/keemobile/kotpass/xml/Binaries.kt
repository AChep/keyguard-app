package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.database.BinaryPool
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.extensions.readBytesOrNull
import app.keemobile.kotpass.extensions.toXmlString
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.XmlContext
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import okio.ByteString

internal fun unmarshalBinaries(
    reader: XmlReader,
    innerEncryption: EncryptionSaltGenerator,
): Map<ByteString, BinaryData> {
    val binaries = BinaryPool()
    reader.forEachChildElement {
        if (reader.isUnqualifiedElement(FormatXml.Tags.Meta.Binaries.Item)) {
            val (ref, binary) = unmarshalBinaryData(reader, innerEncryption)
            binaries.add(ref, binary)
        } else {
            reader.discardKdbxElement(innerEncryption)
        }
    }
    return binaries
}

private fun unmarshalBinaryData(
    reader: XmlReader,
    innerEncryption: EncryptionSaltGenerator,
): Pair<Int, BinaryData> {
    val id = reader.intAttributeOrNull(FormatXml.Attributes.Id)
        ?: throw FormatError.InvalidXml("Binary node has no id.")
    val compressed = reader.booleanAttributeOrNull(FormatXml.Attributes.Compressed) ?: false
    val markers = reader.readProtectedXmlValueMarkers()
    val encoded = reader.readBytesOrNull() ?: ByteArray(0)
    val bytes = if (markers.usesInnerEncryption) {
        try {
            innerEncryption.processBytes(encoded)
        } finally {
            encoded.fill(0)
        }
    } else {
        encoded
    }
    // Accept combined protection/compression by decrypting before lazy decompression,
    // matching KeePassXC and the attachment visitor. KeePass ignores compression on
    // protected input; our writer avoids this ambiguity by never emitting both flags.
    val binary = when {
        compressed -> BinaryData.Compressed(markers.isProtected, bytes)
        else -> BinaryData.Uncompressed(markers.isProtected, bytes)
    }

    return id to binary
}

internal fun BinaryData.marshalTo(id: Int, context: XmlContext.Encode, writer: XmlWriter) {
    val compressed = this is BinaryData.Compressed
    writer.element(FormatXml.Tags.Meta.Binaries.Item) {
        attribute(FormatXml.Attributes.Id, id.toString())
        if (memoryProtection && context is XmlContext.Encode.Encrypted) {
            // Protected pool items are encrypted without compression.
            attribute(FormatXml.Attributes.Protected, FormatXml.Values.True)
            val plaintext = getContent()
            try {
                val ciphertext = context.innerEncryption.processBytes(plaintext)
                try {
                    addBytes(ciphertext)
                } finally {
                    ciphertext.fill(0)
                }
            } finally {
                if (compressed) plaintext.fill(0)
            }
        } else {
            // Plain XML has no portable binary memory-protection marker.
            // ProtectInMemory makes kdbxweb interpret the Base64 payload as a string.
            attribute(FormatXml.Attributes.Compressed, compressed.toXmlString())
            addBytes(rawContent)
        }
    }
}
