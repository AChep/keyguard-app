package com.artemchep.keyguard.common.model

data class Fingerprint(
    val master: FingerprintPassword,
    val biometric: FingerprintBiometric?,
    val yubiKey: FingerprintYubiKey? = null,
    val fido2: FingerprintFido2? = null,
) {
    /**
     * The version of the format, also the label of the master key,
     * see [FingerprintPassword.version].
     */
    val version: MasterKdfVersion
        get() = master.version
}
