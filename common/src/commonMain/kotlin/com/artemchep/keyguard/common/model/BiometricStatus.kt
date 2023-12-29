package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.platform.LeCipher

sealed interface BiometricStatus {
    class Available(
        /**
         * Creates a cipher to use with a biometric
         * prompt.
         */
        val createCipher: (BiometricPurpose) -> LeCipher,
        val deleteCipher: () -> Unit,
    ) : BiometricStatus

    data object Unavailable : BiometricStatus
}
