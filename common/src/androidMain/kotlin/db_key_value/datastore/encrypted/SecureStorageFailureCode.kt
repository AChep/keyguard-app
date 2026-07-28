package db_key_value.datastore.encrypted

internal enum class SecureStorageFailureCode {
    KEYSTORE_BUSY,
    KEY_UNAVAILABLE,
    INTEGRITY_FAILURE,
    STORAGE_UNAVAILABLE,
    UNKNOWN_FAILURE,
}
