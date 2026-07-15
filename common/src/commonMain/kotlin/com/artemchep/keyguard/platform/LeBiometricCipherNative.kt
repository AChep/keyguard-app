package com.artemchep.keyguard.platform

import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives

abstract class LeBiometricCipherNative(
    val forEncryption: Boolean,
) : LeBiometricCipher {
    @Suppress("MemberVisibilityCanBePrivate", "PropertyName")
    var _key: ByteArray? = null

    @Suppress("MemberVisibilityCanBePrivate", "PropertyName")
    var _iv: ByteArray? = null

    override val iv: ByteArray get() = requireNotNull(_iv)

    override fun encode(data: ByteArray): ByteArray {
        val key = requireNotNull(_key) {
            "Cipher key is missing! " +
                    "Entity needs to be populated from a Keychain before " +
                    "it can perform encoding operations."
        }
        return if (forEncryption) {
            NativeCryptoPrimitives.aesCbcPkcs7Encrypt(key, iv, data)
        } else {
            NativeCryptoPrimitives.aesCbcPkcs7Decrypt(key, iv, data)
        }
    }
}
