package com.artemchep.keyguard.platform

import com.artemchep.keyguard.common.model.BiometricBindingException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class LeBiometricCipherWindowsHelloTest {
    @Test
    fun `serialized cipher round trips wrapped secret and encrypted data`() {
        val secret = ByteArray(32) { it.toByte() }
        val aesIv = ByteArray(16) { (it + 32).toByte() }
        val wrappedSecret = ByteArray(256) { (it % 251).toByte() }
        val encryptCipher = LeBiometricCipherWindowsHello.forEncryption(secret, aesIv)
        encryptCipher.completeEncryption(wrappedSecret)

        val decryptCipher = LeBiometricCipherWindowsHello.forDecryption(encryptCipher.iv)
        assertContentEquals(wrappedSecret, decryptCipher.copyWrappedSecret())
        decryptCipher.completeDecryption(secret)

        val plaintext = "Windows Hello unlock".encodeToByteArray()
        assertContentEquals(
            plaintext,
            decryptCipher.encode(encryptCipher.encode(plaintext)),
        )
    }

    @Test
    fun `decrypt cipher rejects an unversioned payload`() {
        assertFailsWith<BiometricBindingException> {
            LeBiometricCipherWindowsHello.forDecryption(ByteArray(64))
        }
    }

    @Test
    fun `decrypt cipher rejects an invalid unwrapped secret size`() {
        val secret = ByteArray(32)
        val encryptCipher = LeBiometricCipherWindowsHello.forEncryption(
            secret = secret,
            aesIv = ByteArray(16),
        ).apply {
            completeEncryption(ByteArray(256))
        }
        val cipher = LeBiometricCipherWindowsHello.forDecryption(encryptCipher.iv)

        assertFailsWith<IllegalArgumentException> {
            cipher.completeDecryption(ByteArray(31))
        }
    }
}
