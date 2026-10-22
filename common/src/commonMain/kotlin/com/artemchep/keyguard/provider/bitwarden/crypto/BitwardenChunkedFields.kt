package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.util.isLowerHexDigit
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.util.foundation.crypto.sha256

/**
 * Splits a value that does not fit Bitwarden's 5,000-character field limit
 * into `<base>.v1.part.<N>` fields and a `<base>.v1.sha256` field.
 */
internal object BitwardenChunkedFields {
    // Under Bitwarden's AES-CBC/HMAC envelope a 3,500-byte value encrypts to
    // 4,744 characters, leaving room below the server's 5,000-character limit.
    const val CHUNK_MAX_UTF8_BYTES = 3_500
    private const val VERSION = "v1"
    private const val PART = "part"
    private const val HASH = "sha256"
    private const val SHA256_HEX_LENGTH = 64

    // UTF-8 continuation bytes carry the 0b10xxxxxx prefix.
    private const val UTF8_BYTE_PREFIX_MASK = 0xC0
    private const val UTF8_CONTINUATION_PREFIX = 0x80

    /** The prefix of the names of every field that carries the value of [baseName]. */
    fun prefix(baseName: String): String = "$baseName.$VERSION."

    fun encode(
        baseName: String,
        value: String,
        type: BitwardenCipher.Field.Type,
    ): List<BitwardenCipher.Field> {
        val bytes = value.encodeToByteArray()
        val parts = bytes.chunkedByUtf8Bytes(CHUNK_MAX_UTF8_BYTES)
            .mapIndexed { index, chunk ->
                BitwardenCipher.Field(
                    name = partPrefix(baseName) + (index + 1),
                    value = chunk,
                    type = type,
                )
            }
        return parts + BitwardenCipher.Field(
            name = hashName(baseName),
            value = sha256(bytes).toHex(),
            type = type,
        )
    }

    /**
     * Assembles the value from the [fields], each of which must be a part
     * or the hash of [baseName]. Returns `null` if the group is malformed.
     */
    @Suppress("ReturnCount")
    fun decode(
        baseName: String,
        fields: List<BitwardenCipher.Field>,
        type: BitwardenCipher.Field.Type,
    ): String? {
        val hashName = hashName(baseName)
        val hashField = fields.singleOrNull { it.name == hashName }
            ?: return null
        val expectedHash = hashField.value
            ?.takeIf { value ->
                value.length == SHA256_HEX_LENGTH &&
                        value.all { char -> char.isLowerHexDigit() }
            }
            ?: return null
        if (hashField.type != type || hashField.linkedId != null) {
            return null
        }

        val partPrefix = partPrefix(baseName)
        val parts = fields
            .filter { it.name != hashName }
            .map { field ->
                field.decodePart(
                    partPrefix = partPrefix,
                    type = type,
                ) ?: return null
            }
            .sortedBy { (index, _) -> index }
        if (parts.isEmpty()) return null
        val contiguous = parts.withIndex().all { (position, part) ->
            part.first == position + 1
        }
        if (!contiguous) return null

        val value = parts.joinToString(separator = "") { (_, chunk) -> chunk }
        val actualHash = sha256(value.encodeToByteArray()).toHex()
        return value.takeIf { actualHash == expectedHash }
    }

    @Suppress("ReturnCount")
    private fun BitwardenCipher.Field.decodePart(
        partPrefix: String,
        type: BitwardenCipher.Field.Type,
    ): Pair<Int, String>? {
        if (this.type != type || linkedId != null) return null
        val value = value
            ?.takeIf(String::isNotEmpty)
            ?: return null
        if (value.encodeToByteArray().size > CHUNK_MAX_UTF8_BYTES) return null
        val rawIndex = name
            ?.takeIf { it.startsWith(partPrefix) }
            ?.substring(partPrefix.length)
            ?: return null
        val index = rawIndex.toIntOrNull()
            // Reject signs, leading zeros and other non-canonical forms.
            ?.takeIf { it > 0 && it.toString() == rawIndex }
            ?: return null
        return index to value
    }

    /**
     * Splits the UTF-8 bytes into chunks of at most [maxBytes] bytes,
     * cutting only at scalar boundaries.
     */
    private fun ByteArray.chunkedByUtf8Bytes(
        maxBytes: Int,
    ): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < size) {
            var end = minOf(start + maxBytes, size)
            // Move the cut left until it lands on the first byte
            // of a UTF-8 sequence.
            while (end < size && this[end].toInt() and UTF8_BYTE_PREFIX_MASK == UTF8_CONTINUATION_PREFIX) {
                end--
            }
            chunks += decodeToString(startIndex = start, endIndex = end)
            start = end
        }
        return chunks
    }

    private fun partPrefix(baseName: String): String = prefix(baseName) + "$PART."

    private fun hashName(baseName: String): String = prefix(baseName) + HASH
}
