package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.LimitedSource
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import okio.Buffer
import okio.Source
import okio.Timeout
import kotlin.io.encoding.Base64

private const val BASE64_DECODE_CHARS = 64 * 1024
private const val BASE64_QUANTUM_CHARS = 4
private const val BINARY_TRANSFER_BYTES = 64 * 1024L

internal fun XmlReader.discardProtectedTextStreaming(
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

internal class InnerEncryptionSource(
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
        if (read > 0L) {
            decryptTransferInto(sink)
        }
        return read
    }

    private fun decryptTransferInto(sink: Buffer) {
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
internal class XmlBase64Source(
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
            encoded.length - encoded.length % BASE64_QUANTUM_CHARS
        }
        if (!final && paddingStarted) {
            count = (count - BASE64_QUANTUM_CHARS).coerceAtLeast(0)
        }
        if (count == 0 && !final) return

        if (final) {
            appendMissingPadding()
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

    /**
     * Restores the omitted terminal padding so the strict decoder accepts
     * the final quantum.
     */
    private fun appendMissingPadding() {
        val remainder = encoded.length % BASE64_QUANTUM_CHARS
        if (remainder == 0) return
        val padding = BASE64_QUANTUM_CHARS - remainder
        if (padding > 2) invalidBase64()
        repeat(padding) { encoded.append('=') }
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

internal fun Source.drain(
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

internal fun Source.readBoundedByteArray(
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
