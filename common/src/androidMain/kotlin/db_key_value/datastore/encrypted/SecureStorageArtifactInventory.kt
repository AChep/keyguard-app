package db_key_value.datastore.encrypted

internal data class SecureStorageArtifactInventory(
    val ciphertextStores: Set<String>,
    val keysetPresent: Boolean,
    val masterKeyPresent: Boolean,
)

/**
 * True when existing ciphertext or key material can no longer be decrypted (e.g. a
 * backup restore that brought the files but not the hardware-backed master key), so
 * the coordinator must wipe and start clean rather than mint a fresh key on top of
 * unreadable data.
 */
internal fun SecureStorageArtifactInventory.isUndecryptable(): Boolean =
    (ciphertextStores.isNotEmpty() && !(keysetPresent && masterKeyPresent)) ||
            (keysetPresent && !masterKeyPresent)
