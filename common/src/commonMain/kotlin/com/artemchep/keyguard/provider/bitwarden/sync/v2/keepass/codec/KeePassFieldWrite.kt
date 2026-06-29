package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.EntryValue

/**
 * A single (field-key, value) write that a sub-codec contributes to a KeePass
 * entry. The top-level [KeePassCipherCodec] replays these onto the entry via the
 * encode scope, so every sub-codec shares one write contract.
 */
internal data class KeePassFieldWrite(
    val key: String,
    val value: EntryValue,
)

/** Wraps [value] as a memory-protected (concealed) or plain KeePass entry value. */
internal fun keepassEntryValue(
    value: String,
    concealed: Boolean,
): EntryValue =
    if (concealed) {
        EntryValue.Encrypted(EncryptedValue.fromString(value))
    } else {
        EntryValue.Plain(value)
    }

/** A single field write for [key] carrying [value] with the given concealment. */
internal fun keepassFieldWrite(
    key: String,
    value: String,
    concealed: Boolean,
): KeePassFieldWrite = KeePassFieldWrite(key, keepassEntryValue(value, concealed))

/** Adds a plain write for [key] unless [value] is null (empty strings are kept). */
internal fun MutableList<KeePassFieldWrite>.addPlain(key: String, value: String?) {
    if (value != null) add(KeePassFieldWrite(key, keepassEntryValue(value, concealed = false)))
}

/** Adds a memory-protected write for [key] unless [value] is null. */
internal fun MutableList<KeePassFieldWrite>.addConcealed(key: String, value: String?) {
    if (value != null) add(KeePassFieldWrite(key, keepassEntryValue(value, concealed = true)))
}
