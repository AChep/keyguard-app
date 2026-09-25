package com.artemchep.keyguard.platform

import platform.LocalAuthentication.LAContext

/**
 * Apple counterpart of the desktop `LeBiometricCipherKeychain`: an AES-CBC
 * cipher whose key lives in the keychain. The key/iv population is deferred
 * until [materialize] so the keychain is only touched after the user passes
 * the biometric check (see the Touch ID prompt host in the macOS bridge).
 */
class LeBiometricCipherApple(
    private val defer: suspend (LeBiometricCipherApple, LAContext) -> Unit,
    /**
     * `true` if the cipher is used to encrypt the data,
     * `false` if the cipher is used to decrypt the data.
     */
    forEncryption: Boolean,
) : LeBiometricCipherNative(forEncryption) {

    suspend fun materialize(context: LAContext) {
        defer(this, context)
    }
}
