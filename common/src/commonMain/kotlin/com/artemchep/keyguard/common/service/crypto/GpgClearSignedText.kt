package com.artemchep.keyguard.common.service.crypto

/**
 * Pure, platform-independent processing of RFC 4880 clear-signed (`--clearsign`)
 * text. This holds only text/byte manipulation — the canonicalization rules that
 * decide which bytes are covered by a signature — so the actual signing and
 * verification (which need OpenPGP primitives) can live on each platform while the
 * byte-exact rules stay shared and testable.
 */

/**
 * A parsed clear-signed message: the canonicalized body [lines] that the signature
 * covers, together with the still-armored [signatureArmored] block that follows the
 * body.
 *
 * Each line is stored with its separator and trailing whitespace already stripped —
 * the exact bytes fed to a signature, in order, joined by CRLF.
 */
internal data class GpgClearSignedMessage(
    val lines: List<ByteArray>,
    val signatureArmored: String,
)

/**
 * One line of clear-signed input as produced by [splitClearTextLines]:
 *  - [raw] is the line exactly as it appeared in the input, including its original
 *    line terminator (`\n`, `\r`, or `\r\n`), so it can be written back verbatim.
 *  - [canonicalLength] is how many leading bytes of [raw] form the canonical content
 *    (the line with its separator and trailing whitespace removed), i.e. the bytes
 *    that are actually covered by the signature.
 */
internal data class GpgClearTextLine(
    val raw: ByteArray,
    val canonicalLength: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
                other is GpgClearTextLine &&
                canonicalLength == other.canonicalLength &&
                raw.contentEquals(other.raw)

    override fun hashCode(): Int = 31 * raw.contentHashCode() + canonicalLength
}

/**
 * Parses a clear-signed message string into its canonicalized body [GpgClearSignedMessage.lines]
 * and the trailing armored signature block.
 *
 * Line endings are first normalized to `\n`; the body is everything between the blank
 * line that ends the headers (`\n\n`) and the `-----BEGIN PGP SIGNATURE-----` marker,
 * with a single trailing `\n` removed. Each body line then has its dash-escape (`"- "`
 * prefix) removed and its trailing whitespace stripped, matching the canonical text the
 * signature was computed over.
 */
internal fun parseClearSignedMessage(
    signedText: String,
): GpgClearSignedMessage {
    val normalized = signedText
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    val signatureMarker = "-----BEGIN PGP SIGNATURE-----"
    // Scan for the blank line that ends the headers starting from the signed-message
    // marker, so leading garbage that happens to contain a blank line cannot be mistaken
    // for the header terminator.
    val headerScanStart = normalized.indexOf("-----BEGIN PGP SIGNED MESSAGE-----")
        .coerceAtLeast(0)
    val headerEnd = normalized.indexOf("\n\n", headerScanStart)
        .takeIf { it >= 0 }
        ?: throw IllegalStateException("The clear-signed message is malformed.")
    // A dash-escaped body line such as "- -----BEGIN PGP SIGNATURE-----" also contains the
    // marker, and the real signature block always follows the whole (escaped) body, so the
    // genuine signature is the LAST occurrence of the marker — never an escaped body line.
    // (The armor writer may even glue that block straight onto the final body line when the
    // body has no trailing newline, so the marker is not always at a line start.)
    val signatureIndex = normalized.lastIndexOf(signatureMarker)
        .takeIf { it > headerEnd }
        ?: throw IllegalStateException("The input does not contain a GPG signature.")
    val body = normalized
        .substring(headerEnd + 2, signatureIndex)
        .removeSuffix("\n")
    val lines = body
        .split('\n')
        .map { line ->
            val unescaped = if (line.startsWith("- ")) {
                line.drop(2)
            } else {
                line
            }
            lineWithoutSeparatorOrTrailingWhitespace(unescaped.encodeToByteArray())
        }
    val signatureArmored = normalized.substring(signatureIndex)
    return GpgClearSignedMessage(
        lines = lines,
        signatureArmored = signatureArmored,
    )
}

/**
 * Splits clear-signed input [input] into its constituent lines while preserving both
 * the raw bytes (for re-emitting the message verbatim) and the canonical content
 * length (for feeding the signature).
 *
 * This replicates the semantics of the streaming line reader gpg uses: a line ends at
 * `\r`, `\n`, or `\r\n`, and the terminator belongs to the raw line. A terminator that
 * is immediately followed by end-of-input does NOT produce a trailing empty line, but
 * empty input still yields exactly one (empty) line.
 */
internal fun splitClearTextLines(
    input: ByteArray,
): List<GpgClearTextLine> {
    val cursor = ByteCursor(input)
    val lines = mutableListOf<GpgClearTextLine>()
    val lineOut = mutableListOf<Byte>()
    var lookAhead = readInputLine(lineOut, cursor)
    lines += lineOut.toClearTextLine()

    if (lookAhead != -1) {
        do {
            lookAhead = readInputLine(lineOut, lookAhead, cursor)
            lines += lineOut.toClearTextLine()
        } while (lookAhead != -1)
    }
    return lines
}

private fun MutableList<Byte>.toClearTextLine(): GpgClearTextLine {
    val raw = toByteArray()
    return GpgClearTextLine(
        raw = raw,
        canonicalLength = lengthWithoutWhitespace(raw),
    )
}

private fun readInputLine(
    out: MutableList<Byte>,
    cursor: ByteCursor,
): Int {
    out.clear()
    var lookAhead = -1
    while (true) {
        val ch = cursor.read()
        if (ch < 0) {
            break
        }
        out.add(ch.toByte())
        if (ch == '\r'.code || ch == '\n'.code) {
            lookAhead = readPassedEol(out, ch, cursor)
            break
        }
    }
    return lookAhead
}

private fun readInputLine(
    out: MutableList<Byte>,
    lookAhead: Int,
    cursor: ByteCursor,
): Int {
    out.clear()
    var ch = lookAhead
    var nextLookAhead = -1
    while (ch >= 0) {
        out.add(ch.toByte())
        if (ch == '\r'.code || ch == '\n'.code) {
            nextLookAhead = readPassedEol(out, ch, cursor)
            break
        }
        ch = cursor.read()
    }
    if (ch < 0) {
        nextLookAhead = -1
    }
    return nextLookAhead
}

private fun readPassedEol(
    out: MutableList<Byte>,
    lastCh: Int,
    cursor: ByteCursor,
): Int {
    var lookAhead = cursor.read()
    if (lastCh == '\r'.code && lookAhead == '\n'.code) {
        out.add(lookAhead.toByte())
        lookAhead = cursor.read()
    }
    return lookAhead
}

/**
 * Returns [line] with its line separator and any trailing whitespace removed — the
 * canonical form the clear-text signature is computed over.
 */
internal fun lineWithoutSeparatorOrTrailingWhitespace(
    line: ByteArray,
): ByteArray = line.copyOf(lengthWithoutWhitespace(line))

/**
 * Returns the length of [line] after trailing whitespace (`\r`, `\n`, `\t`, space) is
 * ignored — i.e. the number of leading bytes that count as canonical content.
 */
internal fun lengthWithoutWhitespace(
    line: ByteArray,
): Int {
    var end = line.size - 1
    while (end >= 0 && isClearTextSignatureWhitespace(line[end])) {
        end--
    }
    return end + 1
}

private fun isClearTextSignatureWhitespace(
    byte: Byte,
): Boolean = byte == '\r'.code.toByte() ||
        byte == '\n'.code.toByte() ||
        byte == '\t'.code.toByte() ||
        byte == ' '.code.toByte()

/**
 * A tiny forward-only cursor over a [ByteArray] whose [read] mirrors `InputStream.read`:
 * it returns the next byte as an unsigned value in `0..255`, or `-1` at end of input.
 */
private class ByteCursor(
    private val bytes: ByteArray,
) {
    private var index = 0

    fun read(): Int = if (index < bytes.size) {
        bytes[index++].toInt() and 0xFF
    } else {
        -1
    }
}
