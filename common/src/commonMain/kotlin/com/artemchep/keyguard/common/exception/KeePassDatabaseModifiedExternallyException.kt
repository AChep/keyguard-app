package com.artemchep.keyguard.common.exception

class KeePassDatabaseModifiedExternallyException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
