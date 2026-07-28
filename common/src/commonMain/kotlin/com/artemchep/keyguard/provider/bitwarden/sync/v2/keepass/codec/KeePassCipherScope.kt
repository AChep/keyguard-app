package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue

/**
 * Accumulates the (field-key, value) pairs to write to a KeePass entry during
 * encoding. Owned by the codec layer and shared by [KeePassCipherCodec] and its
 * sub-codecs, rather than nested inside its heaviest consumer.
 */
internal class EncodeToCipherScope {
    private val fields = mutableMapOf<String, EntryValue>()

    fun setPlain(key: String, value: String?) =
        setValue(key, value?.let(EntryValue::Plain))

    fun setConcealed(key: String, value: String?) =
        setValue(key, value?.let(EncryptedValue::fromString)?.let(EntryValue::Encrypted))

    fun setValue(key: String, value: EntryValue?) {
        if (value == null) return
        val uniqueKey = ensureUniqueKey(key)
        fields[uniqueKey] = value
    }

    private fun ensureUniqueKey(key: String, index: Int = 0): String {
        val finalKey = if (index <= 0) key else "$key #$index"
        if (finalKey !in fields) return finalKey
        return ensureUniqueKey(key, index + 1)
    }

    fun getAvailableFields() = fields
}

/**
 * Tracks which fields/tags of a remote KeePass entry have been consumed during
 * decoding, so the orchestrator can map the leftovers to custom fields. Shared
 * by [KeePassCipherCodec] and its sub-codecs.
 */
internal class DecodeToCipherScope(
    private val remote: Entry,
) {
    private val consumedFields = mutableSetOf<String>()
    private val consumedTags = mutableSetOf<String>()

    fun spitField(key: String) {
        consumedFields.remove(key)
    }

    fun consumeField(key: String): EntryValue? {
        val added = consumedFields.add(key)
        if (!added) return null
        return remote.fields[key]
            ?.takeIf { value ->
                key !in BasicField.keys || !value.isEmpty()
            }
    }

    fun consumeTag(tag: String): String? {
        val added = consumedTags.add(tag)
        if (!added) return null
        return tag.takeIf { remote.tags.contains(tag) }
    }

    fun consumeFieldAndReturnContent(key: String) =
        consumeField(key)?.content

    fun getAvailableFields() = remote.fields
        .filterKeys { it !in consumedFields }
        .filter { entry ->
            entry.key !in BasicField.keys || !entry.value.isEmpty()
        }

    fun getAvailableTags() = remote.tags.filter { it !in consumedTags }
}
