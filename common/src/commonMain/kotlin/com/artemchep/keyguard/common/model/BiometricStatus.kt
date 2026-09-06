package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.platform.LeBiometricCipher

sealed interface BiometricStatus {
    class Available(
        /**
         * Creates a cipher to use with a biometric
         * prompt.
         */
        val createCipher: suspend (BiometricPurpose) -> LeBiometricCipher,
    ) : BiometricStatus

    data object Unavailable : BiometricStatus
}
