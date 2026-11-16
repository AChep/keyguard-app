package com.artemchep.keyguard.common.service.keyvalue

/**
 * Thrown by a store that was asked to fail instead of returning default
 * values when it cannot be opened. The failure may be temporary, so callers
 * may retry later.
 */
class KeyValueStoreUnavailableException(
    cause: Throwable,
) : RuntimeException("The key-value store is unavailable", cause)
