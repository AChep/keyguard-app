package com.artemchep.keyguard.common.model

sealed interface BiometricPurpose {
    data object Encrypt : BiometricPurpose

    data class Decrypt(
        val iv: DKey,
    ) : BiometricPurpose
}
