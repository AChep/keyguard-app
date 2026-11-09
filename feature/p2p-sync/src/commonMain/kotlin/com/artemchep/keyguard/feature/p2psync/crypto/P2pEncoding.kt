package com.artemchep.keyguard.feature.p2psync.crypto

import com.artemchep.keyguard.feature.p2psync.BYTE_BITS
import com.artemchep.keyguard.feature.p2psync.unsignedAt
import kotlin.io.encoding.Base64

/** The text encodings of keys, hashes and codes used on the wire. */
object P2pEncoding {
    private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    private val base64UrlWriter = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val base64UrlReader = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /** Encodes [bytes] as base64url without padding. */
    fun base64Url(bytes: ByteArray): String = base64UrlWriter.encode(bytes)

    /** Decodes base64url text with or without padding, or returns `null` if it is not valid. */
    fun base64UrlOrNull(text: String): ByteArray? = try {
        base64UrlReader.decode(text)
    } catch (expected: IllegalArgumentException) {
        null
    }

    /**
     * Decodes base64url text that is canonical, the one text [base64Url] writes for its
     * bytes: unpadded, with no stray bits. Returns `null` for any other text.
     */
    fun canonicalBase64UrlOrNull(text: String): ByteArray? =
        base64UrlOrNull(text)?.takeIf { base64Url(it) == text }

    /** Encodes [bytes] as lowercase RFC 4648 base32 without padding. */
    fun base32(bytes: ByteArray): String = bytes.toBase32(BASE32_ALPHABET)

    /** Encodes [bytes] as uppercase RFC 4648 base32 without padding. */
    fun base32UpperCase(bytes: ByteArray): String = bytes.toBase32(BASE32_ALPHABET.uppercase())

    /**
     * Decodes RFC 4648 base32 text without padding, in either case. Returns `null` for any
     * text that is not the encoding of some bytes: another character, a length no bytes
     * encode to, or stray bits.
     */
    fun base32OrNull(text: String): ByteArray? = text.fromBase32OrNull()

    /** Encodes [bytes] as lowercase hexadecimal. */
    fun hex(bytes: ByteArray): String = bytes.toHexString()
}

/** The bits one base32 character encodes. */
private const val BASE32_BITS = 5

private const val BASE32_MASK = (1 shl BASE32_BITS) - 1

/** The value of one base32 character, in either case, or -1. */
private fun base32Value(char: Char): Int = when (char) {
    in 'a'..'z' -> char - 'a'
    in 'A'..'Z' -> char - 'A'
    in '2'..'7' -> char - '2' + ('z' - 'a' + 1)
    else -> -1
}

private fun String.fromBase32OrNull(): ByteArray? {
    val out = ByteArray(length * BASE32_BITS / BYTE_BITS)
    var buffer = 0
    var bits = 0
    var index = 0
    for (char in this) {
        val value = base32Value(char)
        if (value < 0) return null
        buffer = (buffer shl BASE32_BITS) or value
        bits += BASE32_BITS
        if (bits >= BYTE_BITS) {
            bits -= BYTE_BITS
            out[index++] = (buffer shr bits).toByte()
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    // A last character must carry a byte, and the bits after it must be zero.
    return out.takeIf { bits < BASE32_BITS && buffer == 0 }
}

private fun ByteArray.toBase32(alphabet: String): String = buildString {
    var buffer = 0
    var bits = 0
    for (index in this@toBase32.indices) {
        buffer = (buffer shl BYTE_BITS) or this@toBase32.unsignedAt(index)
        bits += BYTE_BITS
        while (bits >= BASE32_BITS) {
            bits -= BASE32_BITS
            append(alphabet[(buffer shr bits) and BASE32_MASK])
        }
        buffer = buffer and ((1 shl bits) - 1)
    }
    if (bits > 0) {
        append(alphabet[(buffer shl (BASE32_BITS - bits)) and BASE32_MASK])
    }
}
