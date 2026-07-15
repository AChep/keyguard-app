package com.artemchep.keyguard.platform

/**
 * Apple counterpart of the desktop `LeBiometricCipherKeychain`: an AES-CBC
 * cipher whose key lives in the keychain. The key/iv population is deferred
 * until [materialize] so the keychain is only touched after the user passes
 * the biometric check (see the Touch ID prompt host in the macOS bridge).
 */
class LeBiometricCipherApple(
    private val defer: suspend (LeBiometricCipherApple) -> Unit,
    /**
     * `true` if the cipher is used to encrypt the data,
     * `false` if the cipher is used to decrypt the data.
     */
    forEncryption: Boolean,
) : LeBiometricCipherNative(forEncryption) {

    suspend fun materialize() {
        defer(this)
    }
}
