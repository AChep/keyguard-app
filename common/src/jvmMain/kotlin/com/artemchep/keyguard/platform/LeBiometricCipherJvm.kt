package com.artemchep.keyguard.platform

import javax.crypto.Cipher

class LeBiometricCipherJvm(
    val cipher: Cipher,
) : LeBiometricCipher {
    override val iv: ByteArray get() = cipher.iv

    override fun encode(data: ByteArray): ByteArray {
        // Cipher is not really thread-safe as
        // far as I remember.
        return synchronized(cipher) {
            cipher.doFinal(data)
        }
    }
}
