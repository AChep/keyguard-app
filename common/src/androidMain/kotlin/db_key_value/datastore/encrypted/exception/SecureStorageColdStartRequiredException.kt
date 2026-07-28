package db_key_value.datastore.encrypted.exception

/**
 * Global key recovery must run before any encrypted DataStore is opened. Once a
 * store is active, callers surface this failure and defer the destructive recovery
 * until the next cold process start.
 */
internal class SecureStorageColdStartRequiredException(
    cause: Throwable? = null,
) : SecureStorageInitializationException(cause)
