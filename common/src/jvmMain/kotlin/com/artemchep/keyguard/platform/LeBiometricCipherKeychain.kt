package com.artemchep.keyguard.platform

class LeBiometricCipherKeychain(
    private val defer: (LeBiometricCipherKeychain) -> Unit,
    /**
     * `true` if the cipher is used to encrypt the data,
     * `false` if the cipher is used to decrypt the data.
     */
    forEncryption: Boolean,
) : LeBiometricCipherNative(forEncryption) {

    fun materialize() {
        defer(this)
    }
}
