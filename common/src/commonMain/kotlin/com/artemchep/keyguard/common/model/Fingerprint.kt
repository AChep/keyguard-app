package com.artemchep.keyguard.common.model

data class Fingerprint(
    val kdf: MasterKdf,
    val master: FingerprintPassword,
    val biometric: FingerprintBiometric?,
    val yubiKey: FingerprintYubiKey? = null,
    val fido2: FingerprintFido2? = null,
)
