package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.LimitedSource
import app.keemobile.kotpass.io.MAX_DECOMPRESSED_SIZE
import app.keemobile.kotpass.io.gunzipSource
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlException
import nl.adaptivity.xmlutil.XmlReader
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Source
import okio.Timeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

private const val BASE64_DECODE_CHARS = 64 * 1024
private const val BINARY_TRANSFER_BYTES = 64 * 1024L

internal fun interface XmlBinaryContentVisitor {
    /**
     * [source] contains the uncompressed attachment bytes and is valid only
     * until this callback returns. [declaredLength] is always `null` here:
     * XML-embedded binaries are base64 text whose decoded size is unknown
     * until the stream ends.
     */
    fun visit(source: Source, declaredLength: Long?)
}

internal fun visitXmlBinaryContents(
    source: BufferedSource,
    innerEncryption: EncryptionSaltGenerator,
    visitor: XmlBinaryContentVisitor,
    limits: XmlReadLimits = XmlReadLimits.Default,
    checkCancellation: () -> Unit = {},
): ByteString? {
    var headerHash: ByteString? = null
    try {
        val reader = xmlReader(source, limits)
        reader.enterDocumentRoot()
            ?: throw FormatError.InvalidXml("No root found.")
        if (!reader.isUnqualifiedElement(FormatXml.Tags.Document)) {
            throw FormatError.InvalidXml("Unexpected document root '${reader.localName}'.")
        }
        reader.scanBinaryElement(
            innerEncryption = innerEncryption,
            visitor = visitor,
            parentName = null,
            grandparentName = null,
            entryBinaryContainer = false,
            onHeaderHash = { value -> headerHash = value },
            checkCancellation = checkCancellation,
        )
        reader.finishDocument()
    } catch (e: FormatError) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: XmlException) {
        throw FormatError.InvalidXml(
            e.message ?: "Malformed XML document.",
            e,
        )
    } catch (e: Exception) {
        throw FormatError.InvalidXml(
            e.message ?: "Malformed XML document.",
            e,
        )
    }
    return headerHash
}

private fun XmlReader.scanBinaryElement(
    innerEncryption: EncryptionSaltGenerator,
    visitor: XmlBinaryContentVisitor,
    parentName: String?,
    grandparentName: String?,
    entryBinaryContainer: Boolean,
    onHeaderHash: (ByteString?) -> Unit,
    checkCancellation: () -> Unit,
) {
    checkCancellation()
    val currentName = localName
    val unqualified = namespaceURI.isEmpty()
    val isPooledBinary =
        unqualified &&
            currentName == FormatXml.Tags.Meta.Binaries.Item &&
            parentName == FormatXml.Tags.Meta.Binaries.TagName &&
            grandparentName == FormatXml.Tags.Meta.TagName
    val isInlineBinaryValue =
        unqualified &&
            currentName == FormatXml.Tags.Entry.BinaryReferences.ItemValue &&
            entryBinaryContainer &&
            attributeOrNull(FormatXml.Attributes.Ref) == null

    if (isPooledBinary || isInlineBinaryValue) {
        val compressed = booleanAttributeOrNull(FormatXml.Attributes.Compressed) ?: false
        val markers = if (isInlineBinaryValue) {
            readProtectedXmlValueMarkers()
        } else {
            XmlProtectedValueMarkers(
                usesInnerEncryption = false,
                protectsInMemory = false,
            )
        }
        visitBinaryText(
            innerEncryption = innerEncryption,
            markers = markers,
            compressed = compressed,
            visitor = visitor,
            checkCancellation = checkCancellation,
        )
        return
    }

    if (
        unqualified &&
        currentName == FormatXml.Tags.Meta.HeaderHash &&
        parentName == FormatXml.Tags.Meta.TagName
    ) {
        val base64 = XmlBase64Source(this, checkCancellation)
        try {
            val data = base64.readBoundedByteArray(maximumBytes = 32)
            onHeaderHash(data?.toByteString())
        } finally {
            base64.close()
        }
        return
    }

    val markers = readProtectedXmlValueMarkers()
    if (markers.isProtected) {
        discardProtectedTextStreaming(
            markers = markers,
            innerEncryption = innerEncryption,
            checkCancellation = checkCancellation,
        )
        return
    }

    val childIsEntryBinary =
        unqualified &&
            currentName == FormatXml.Tags.Entry.BinaryReferences.TagName &&
            parentName == FormatXml.Tags.Entry.TagName
    forEachChildElement {
        scanBinaryElement(
            innerEncryption = innerEncryption,
            visitor = visitor,
            parentName = currentName,
            grandparentName = parentName,
            entryBinaryContainer = childIsEntryBinary,
            onHeaderHash = onHeaderHash,
            checkCancellation = checkCancellation,
        )
    }
}

private fun XmlReader.visitBinaryText(
    innerEncryption: EncryptionSaltGenerator,
    markers: XmlProtectedValueMarkers,
    compressed: Boolean,
    visitor: XmlBinaryContentVisitor,
    checkCancellation: () -> Unit,
) {
    var content: Source = XmlBase64Source(this, checkCancellation)
    if (markers.usesInnerEncryption) {
        content = InnerEncryptionSource(
            delegate = content,
            innerEncryption = innerEncryption,
        )
    }
    if (compressed) {
        content = content.gunzipSource(MAX_DECOMPRESSED_SIZE)
    } else {
        content = LimitedSource(
            delegate = content,
            maximumBytes = MAX_DECOMPRESSED_SIZE,
            limitExceeded = {
                FormatError.InvalidContent(
                    "Binary content exceeds $MAX_DECOMPRESSED_SIZE bytes.",
                )
            },
        )
    }
    try {
        visitor.visit(content, null)
        content.drain(checkCancellation)
    } finally {
        content.close()
    }
}

private fun XmlReader.discardProtectedTextStreaming(
    markers: XmlProtectedValueMarkers,
    innerEncryption: EncryptionSaltGenerator,
    checkCancellation: () -> Unit,
) {
    if (!markers.usesInnerEncryption) {
        skipElement()
        return
    }
    val ciphertext = XmlBase64Source(this, checkCancellation)
    try {
        val transfer = Buffer()
        while (true) {
            checkCancellation()
            val read = ciphertext.read(transfer, BINARY_TRANSFER_BYTES)
            if (read == -1L) break
            val bytes = transfer.readByteArray()
            try {
                innerEncryption.processBytes(bytes).fill(0)
            } finally {
                bytes.fill(0)
            }
        }
    } finally {
        ciphertext.close()
    }
}

private class InnerEncryptionSource(
    private val delegate: Source,
    private val innerEncryption: EncryptionSaltGenerator,
) : Source {
    private val transfer = Buffer()

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L
        val read = delegate.read(transfer, minOf(byteCount, BINARY_TRANSFER_BYTES))
        if (read <= 0L) return read
        val ciphertext = transfer.readByteArray()
        val plaintext = try {
            innerEncryption.processBytes(ciphertext)
        } finally {
            ciphertext.fill(0)
        }
        try {
            sink.write(plaintext)
        } finally {
            plaintext.fill(0)
        }
        return read
    }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() {
        transfer.clear()
        delegate.close()
    }
}

/**
 * Incrementally decodes Base64 text from the XML element at the reader's
 * current start tag. Whitespace and omitted terminal padding are accepted to
 * match the existing scalar decoder.
 */
private class XmlBase64Source(
    private val reader: XmlReader,
    private val checkCancellation: () -> Unit,
) : Source {
    private val decoded = Buffer()
    private val encoded = StringBuilder(BASE64_DECODE_CHARS)
    private var paddingStarted = false
    private var paddingChars = 0
    private var finished = false
    private var closed = false

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "Base64 XML source is closed" }
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L
        while (decoded.size == 0L && !finished) {
            readNextEvent()
        }
        return if (decoded.size > 0L) {
            decoded.read(sink, minOf(byteCount, decoded.size))
        } else {
            -1L
        }
    }

    private fun readNextEvent() {
        checkCancellation()
        reader.readStreamingTextChunk(BASE64_DECODE_CHARS)?.let { value ->
            append(value)
            return
        }
        when (reader.next()) {
            EventType.TEXT,
            EventType.CDSECT,
            EventType.ENTITY_REF,
            EventType.IGNORABLE_WHITESPACE,
            -> append(reader.text)

            EventType.START_ELEMENT -> throw FormatError.InvalidXml(
                "Binary value must contain text only.",
            )

            EventType.END_ELEMENT -> {
                decodeAvailable(final = true)
                finished = true
            }

            EventType.END_DOCUMENT -> throw FormatError.InvalidXml(
                "Unexpected end of document.",
            )

            else -> Unit
        }
    }

    private fun append(value: String) {
        value.forEach { char ->
            if (char.isWhitespace()) return@forEach
            when {
                char == '=' -> {
                    paddingStarted = true
                    paddingChars += 1
                    if (paddingChars > 2) invalidBase64()
                }

                paddingStarted -> invalidBase64()
            }
            encoded.append(char)
        }
        if (encoded.length >= BASE64_DECODE_CHARS) {
            decodeAvailable(final = false)
        }
    }

    private fun decodeAvailable(
        final: Boolean,
    ) {
        if (encoded.isEmpty()) return
        var count = if (final) {
            encoded.length
        } else {
            encoded.length - encoded.length % 4
        }
        if (!final && paddingStarted) {
            count = (count - 4).coerceAtLeast(0)
        }
        if (count == 0 && !final) return

        if (final) {
            when (encoded.length % 4) {
                0 -> Unit
                2 -> encoded.append("==")
                3 -> encoded.append('=')
                else -> invalidBase64()
            }
            count = encoded.length
        }
        val bytes = try {
            Base64.Default.decode(encoded, 0, count)
        } catch (_: IllegalArgumentException) {
            invalidBase64()
        }
        try {
            decoded.write(bytes)
        } finally {
            bytes.fill(0)
        }
        if (final) {
            encoded.clear()
        } else {
            encoded.deleteRange(0, count)
        }
    }

    private fun invalidBase64(): Nothing = throw FormatError.InvalidXml(
        "Binary value contains invalid Base64.",
    )

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        if (closed) return
        try {
            if (!finished) drain(checkCancellation)
        } finally {
            closed = true
            decoded.clear()
            encoded.clear()
        }
    }
}

private fun Source.drain(
    checkCancellation: () -> Unit = {},
) {
    val discard = Buffer()
    while (true) {
        checkCancellation()
        val read = read(discard, BINARY_TRANSFER_BYTES)
        if (read == -1L) return
        discard.clear()
    }
}

private fun Source.readBoundedByteArray(
    maximumBytes: Long,
): ByteArray? {
    val limited = LimitedSource(
        delegate = this,
        maximumBytes = maximumBytes,
        limitExceeded = {
            FormatError.InvalidXml("Binary scalar exceeds $maximumBytes bytes.")
        },
    )
    val output = Buffer()
    while (limited.read(output, BINARY_TRANSFER_BYTES) != -1L) {
        // Keep reading; the limiter throws once the bound is exceeded.
    }
    return output.readByteArray().takeIf { it.isNotEmpty() }
}
