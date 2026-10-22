package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

/**
 * Encodes and decodes Keyguard's GPG-key representation in Bitwarden custom fields.
 *
 * Values that fit within [BitwardenChunkedFields.CHUNK_MAX_UTF8_BYTES] retain the legacy single-field
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
        if (contentBytes.size <= BitwardenChunkedFields.CHUNK_MAX_UTF8_BYTES) {
            addGpgField(
                name = name,
                value = content,
                type = type,
            )
            return
        }

        addAll(BitwardenChunkedFields.encode(name, content, type))
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
            val chunkPrefix = BitwardenChunkedFields.prefix(name)
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

        val value = BitwardenChunkedFields.decode(
            baseName = name,
            fields = chunkFields.map { (_, field) -> field },
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

    private val CHUNKABLE_FIELD_NAMES = setOf(
        GpgAgentFields.PRIVATE_KEY_ARMORED,
        GpgAgentFields.PUBLIC_KEY_ARMORED,
    )

    private val FIELD_NAMES = CHUNKABLE_FIELD_NAMES + GpgAgentFields.FINGERPRINT

    private val CHUNK_FIELD_PREFIXES = CHUNKABLE_FIELD_NAMES
        .map(BitwardenChunkedFields::prefix)

}
