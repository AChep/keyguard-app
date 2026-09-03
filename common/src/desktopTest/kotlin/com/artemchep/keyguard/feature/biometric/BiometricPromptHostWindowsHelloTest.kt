package com.artemchep.keyguard.feature.biometric

import com.artemchep.autotype.BiometricsException
import com.artemchep.autotype.BiometricsStatus
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.platform.LeBiometricCipherWindowsHello
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BiometricPromptHostWindowsHelloTest {
    @Test
    fun `canceled provisioning clears the pending cipher secret`() = runTest {
        val cancellation = BiometricsException(
            status = BiometricsStatus.USER_CANCELED,
            message = "Canceled.",
        )
        val operations = FakeWindowsHelloBiometricOperations(
            wrapFailure = cancellation,
        )
        val cipher = encryptionCipher()
        val pendingSecret = cipher.pendingSecretReference()

        val exception = assertFailsWith<BiometricsException> {
            host(operations).materialize(request, cipher)
        }

        assertSame(cancellation, exception)
        assertFailsWith<IllegalArgumentException> {
            cipher.copySecretToWrap()
        }
        assertContentEquals(ByteArray(SECRET.size), pendingSecret)
    }

    @Test
    fun `successful provisioning keeps the credential and materializes the cipher`() = runTest {
        val operations = FakeWindowsHelloBiometricOperations()
        val cipher = encryptionCipher()

        host(operations).materialize(request, cipher)

        cipher.iv
    }

    private fun host(operations: WindowsHelloBiometricOperations) =
        BiometricPromptHostWindowsHello(
            cryptoGenerator = CryptoGeneratorJvm(),
            operations = operations,
        )

    private fun encryptionCipher() = LeBiometricCipherWindowsHello.forEncryption(
        secret = SECRET.copyOf(),
        aesIv = ByteArray(16),
    )

    private fun LeBiometricCipherWindowsHello.pendingSecretReference(): ByteArray {
        val field = javaClass.getDeclaredField("secretToWrap").apply {
            isAccessible = true
        }
        return field.get(this) as ByteArray
    }

    private class FakeWindowsHelloBiometricOperations(
        private val wrapFailure: Throwable? = null,
    ) : WindowsHelloBiometricOperations {
        override suspend fun wrapSecret(
            windowHandle: Long,
            title: String,
            secret: ByteArray,
        ): ByteArray {
            wrapFailure?.let { throw it }
            return WRAPPED_SECRET.copyOf()
        }

        override suspend fun unwrapSecret(
            windowHandle: Long,
            title: String,
            wrappedSecret: ByteArray,
        ): ByteArray {
            return SECRET.copyOf()
        }
    }

    companion object {
        private val SECRET = ByteArray(32) { it.toByte() }
        private val WRAPPED_SECRET = ByteArray(256) { (it % 251).toByte() }
        private val request = BiometricPromptRequest(
            title = "Windows Hello unlock",
            windowHandle = 42L,
        )
    }
}
