package com.artemchep.keyguard.platform

import com.artemchep.keyguard.crypto.aesCbcPkcs7
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt

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
    val forEncryption: Boolean,
) : LeBiometricCipher {
    @Suppress("MemberVisibilityCanBePrivate", "PropertyName")
    var _key: ByteArray? = null

    @Suppress("MemberVisibilityCanBePrivate", "PropertyName")
    var _iv: ByteArray? = null

    override val iv: ByteArray get() = requireNotNull(_iv)

    @OptIn(ExperimentalForeignApi::class)
    override fun encode(data: ByteArray): ByteArray {
        val key = requireNotNull(_key) {
            "Cipher key is missing! " +
                    "Entity needs to be populated from a Keychain before " +
                    "it can perform encoding operations."
        }
        return aesCbcPkcs7(
            data = data,
            iv = iv,
            key = key,
            operation = if (forEncryption) kCCEncrypt else kCCDecrypt,
        )
    }

    suspend fun materialize() {
        defer(this)
    }
}
