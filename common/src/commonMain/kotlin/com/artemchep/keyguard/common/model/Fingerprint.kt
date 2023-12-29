package com.artemchep.keyguard.common.model

data class Fingerprint(
    val master: FingerprintPassword,
    val biometric: FingerprintBiometric?,
)
