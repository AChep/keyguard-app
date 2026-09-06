package com.artemchep.keyguard.common.model

/**
 * Indicates that the persisted biometric unlock binding cannot be used again
 * and must be recreated after unlocking with the master password.
 */
class BiometricBindingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
