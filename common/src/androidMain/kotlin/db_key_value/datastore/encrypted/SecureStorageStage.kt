package db_key_value.datastore.encrypted

internal enum class SecureStorageStage {
    ARTIFACT_INVENTORY,
    MASTER_KEY,
    KEYSET,
    PAYLOAD_READ,
}
