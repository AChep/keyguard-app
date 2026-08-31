package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.common.util.isLowerHexDigit
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.util.foundation.crypto.sha256

/**
 * Encodes and decodes Keyguard's GPG-key representation in Bitwarden custom fields.
 *
 * Values that fit within [CHUNK_MAX_UTF8_BYTES] retain the legacy single-field
 * representation. Larger values use versioned, hashed chunks. A rejected aggregate
 * returns no decoded result, allowing the transport to preserve every source field.
 */
@Suppress("TooManyFunctions")
internal object BitwardenGpgKeyFieldCodec {
    data class Decoded(
        val gpgKey: BitwardenCipher.GpgKey,
        val remainingFields: List<BitwardenCipher.Field>,
    )

    /**
     * Only these types may carry GPG key custom fields. Other types must
     * keep user-created fields with matching names untouched.
     */
    fun appliesTo(type: BitwardenCipher.Type): Boolean =
        type == BitwardenCipher.Type.SecureNote ||
                type == BitwardenCipher.Type.GpgKey

    fun encode(
        gpgKey: BitwardenCipher.GpgKey,
        fields: List<BitwardenCipher.Field>,
    ): List<BitwardenCipher.Field> {
        if (gpgKey.takeUnlessEmpty() == null) return fields
        val remainingFields = fields.filterNot { field ->
            field.name.isGpgTransportFieldName()
        }
        return gpgKey.toFields() + remainingFields
    }

    @Suppress("ReturnCount")
    fun decode(
        fields: List<BitwardenCipher.Field>,
    ): Decoded? {
        if (fields.none { it.name.isGpgTransportFieldName() }) {
            return null
        }
        val privateKey = fields.decodeGpgContentFields(
            name = GpgAgentFields.PRIVATE_KEY_ARMORED,
            chunkType = BitwardenCipher.Field.Type.Hidden,
        ) ?: return null
        val publicKey = fields.decodeGpgContentFields(
            name = GpgAgentFields.PUBLIC_KEY_ARMORED,
            chunkType = BitwardenCipher.Field.Type.Text,
        ) ?: return null
        val fingerprint = fields.decodeGpgContentFields(
            name = GpgAgentFields.FINGERPRINT,
            chunkType = null,
        ) ?: return null

        val gpgKey = BitwardenCipher.GpgKey(
            privateKeyArmored = privateKey.value,
            publicKeyArmored = publicKey.value,
            fingerprint = fingerprint.value,
        ).takeUnlessEmpty()
            ?: return null
        val consumedIndexes = privateKey.consumedIndexes +
                publicKey.consumedIndexes +
                fingerprint.consumedIndexes
        return Decoded(
            gpgKey = gpgKey,
            remainingFields = fields.filterIndexed { index, _ ->
                index !in consumedIndexes
            },
        )
    }

    private fun BitwardenCipher.GpgKey.toFields(): List<BitwardenCipher.Field> = buildList {
        addGpgContentFields(
            name = GpgAgentFields.PRIVATE_KEY_ARMORED,
            value = privateKeyArmored,
            type = BitwardenCipher.Field.Type.Hidden,
        )
        addGpgContentFields(
            name = GpgAgentFields.PUBLIC_KEY_ARMORED,
            value = publicKeyArmored,
            type = BitwardenCipher.Field.Type.Text,
        )
        fingerprint
            ?.takeIf { it.isNotBlank() }
            ?.let { fingerprint ->
                addGpgField(
                    name = GpgAgentFields.FINGERPRINT,
                    value = fingerprint,
                    type = BitwardenCipher.Field.Type.Text,
                )
            }
    }

    private fun MutableList<BitwardenCipher.Field>.addGpgContentFields(
        name: String,
        value: String?,
        type: BitwardenCipher.Field.Type,
    ) {
        val content = value?.takeIf { it.isNotBlank() }
            ?: return
        val contentBytes = content.encodeToByteArray()
        if (contentBytes.size <= CHUNK_MAX_UTF8_BYTES) {
            addGpgField(
                name = name,
                value = content,
                type = type,
            )
            return
        }

        contentBytes.chunkedByUtf8Bytes(CHUNK_MAX_UTF8_BYTES)
            .forEachIndexed { index, chunk ->
                addGpgField(
                    name = chunkPartFieldName(name, index + 1),
                    value = chunk,
                    type = type,
                )
            }
        addGpgField(
            name = chunkHashFieldName(name),
            value = sha256(contentBytes).toHex(),
            type = type,
        )
    }

    private fun MutableList<BitwardenCipher.Field>.addGpgField(
        name: String,
        value: String,
        type: BitwardenCipher.Field.Type,
    ) {
        add(
            BitwardenCipher.Field(
                name = name,
                value = value,
                type = type,
            ),
        )
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

    private class DecodedContent(
        val value: String?,
        val consumedIndexes: Set<Int>,
    )

    /**
     * Decodes a single GPG component from the legacy single-field
     * representation and, when [chunkType] is given, the chunked one.
     * Returns `null` when the chunked representation is malformed.
     */
    @Suppress("ReturnCount")
    private fun List<BitwardenCipher.Field>.decodeGpgContentFields(
        name: String,
        chunkType: BitwardenCipher.Field.Type?,
    ): DecodedContent? {
        val legacyFields = withIndex().filter { (_, field) ->
            field.name == name && field.hasDecodableGpgShape()
        }
        val chunkFields = if (chunkType != null) {
            val chunkPrefix = chunkFieldPrefix(name)
            withIndex().filter { (_, field) ->
                field.name?.startsWith(chunkPrefix) == true
            }
        } else {
            emptyList()
        }
        if (chunkType == null || chunkFields.isEmpty()) {
            return DecodedContent(
                value = legacyFields.firstNotNullOfOrNull { (_, field) -> field.value },
                consumedIndexes = legacyFields.mapTo(mutableSetOf()) { (index, _) -> index },
            )
        }

        val value = chunkFields.decodeChunkedGpgContent(
            name = name,
            type = chunkType,
        ) ?: return null
        if (legacyFields.any { (_, field) -> field.value != value }) return null
        return DecodedContent(
            value = value,
            consumedIndexes = buildSet {
                legacyFields.mapTo(this) { (index, _) -> index }
                chunkFields.mapTo(this) { (index, _) -> index }
            },
        )
    }

    /**
     * Assembles the value from its chunk part fields, validating the
     * group and its hash. Returns `null` when the group is malformed.
     */
    @Suppress("ReturnCount")
    private fun List<IndexedValue<BitwardenCipher.Field>>.decodeChunkedGpgContent(
        name: String,
        type: BitwardenCipher.Field.Type,
    ): String? {
        val hashName = chunkHashFieldName(name)
        val hashFields = filter { (_, field) -> field.name == hashName }
        if (hashFields.size != 1) return null
        val hashField = hashFields.single().value
        val expectedHash = hashField.value
            ?.takeIf { value ->
                value.length == SHA256_HEX_LENGTH &&
                        value.all { char -> char.isLowerHexDigit() }
            }
            ?: return null
        if (hashField.type != type || hashField.linkedId != null) {
            return null
        }

        val partPrefix = chunkPartFieldPrefix(name)
        val parts = this
            .filter { (_, field) -> field.name != hashName }
            .map { (_, field) ->
                field.decodeGpgChunkPart(
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
    private fun BitwardenCipher.Field.decodeGpgChunkPart(
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

    private fun BitwardenCipher.Field.hasDecodableGpgShape(): Boolean {
        if (linkedId != null) return false
        return type == BitwardenCipher.Field.Type.Text ||
                type == BitwardenCipher.Field.Type.Hidden
    }

    private fun String?.isGpgTransportFieldName(): Boolean {
        val fieldName = this ?: return false
        return fieldName in FIELD_NAMES ||
                CHUNK_FIELD_PREFIXES.any { prefix -> fieldName.startsWith(prefix) }
    }

    private fun BitwardenCipher.GpgKey.takeUnlessEmpty(): BitwardenCipher.GpgKey? =
        takeIf {
            privateKeyArmored?.isNotBlank() == true ||
                    publicKeyArmored?.isNotBlank() == true ||
                    fingerprint?.isNotBlank() == true ||
                    metadata != null
        }

    private fun chunkFieldPrefix(baseName: String): String =
        "$baseName.$CHUNK_VERSION."

    private fun chunkPartFieldPrefix(baseName: String): String =
        chunkFieldPrefix(baseName) + "$CHUNK_PART."

    private fun chunkPartFieldName(
        baseName: String,
        index: Int,
    ): String = chunkPartFieldPrefix(baseName) + index

    private fun chunkHashFieldName(baseName: String): String =
        chunkFieldPrefix(baseName) + CHUNK_HASH

    private val CHUNKABLE_FIELD_NAMES = setOf(
        GpgAgentFields.PRIVATE_KEY_ARMORED,
        GpgAgentFields.PUBLIC_KEY_ARMORED,
    )

    private val FIELD_NAMES = CHUNKABLE_FIELD_NAMES + GpgAgentFields.FINGERPRINT

    private val CHUNK_FIELD_PREFIXES = CHUNKABLE_FIELD_NAMES
        .map { baseName -> chunkFieldPrefix(baseName) }

    // Under Bitwarden's AES-CBC/HMAC envelope a 3,500-byte value encrypts to
    // 4,744 characters, leaving room below the server's 5,000-character limit.
    private const val CHUNK_MAX_UTF8_BYTES = 3_500
    private const val CHUNK_VERSION = "v1"
    private const val CHUNK_PART = "part"
    private const val CHUNK_HASH = "sha256"
    private const val SHA256_HEX_LENGTH = 64

    // UTF-8 continuation bytes carry the 0b10xxxxxx prefix.
    private const val UTF8_BYTE_PREFIX_MASK = 0xC0
    private const val UTF8_CONTINUATION_PREFIX = 0x80
}
