package com.artemchep.keyguard.common.model

data class Fingerprint(
    val version: MasterKdfVersion,
    val master: FingerprintPassword,
    val biometric: FingerprintBiometric?,
)
