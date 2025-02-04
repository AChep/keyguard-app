package com.artemchep.keyguard.platform

import com.artemchep.keyguard.crypto.util.createAesCbc
import com.artemchep.keyguard.crypto.util.encode

class LeBiometricCipherKeychain(
    private val defer: (LeBiometricCipherKeychain) -> Unit,
    /**
     * `true` if the cipher is used to encrypt the data,
     * `false` if the cipher is used to decrypt the data.
     */
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
        val aes = createAesCbc(
            iv = iv,
            key = key,
            forEncryption = forEncryption,
        )
        return aes.encode(data)
    }

    fun materialize() {
        defer(this)
    }
}
