package db_key_value.datastore.encrypted.exception

import java.security.GeneralSecurityException

internal open class SecureStorageInitializationException(
    cause: Throwable? = null,
) : GeneralSecurityException(
    "Secure storage initialization failed",
    cause,
)
