package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.keychain.KeychainIds
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.platform.AppleBiometricKeychain
import com.artemchep.keyguard.platform.LeBiometricCipherApple
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

/** Mirrors the desktop `BiometricStatusUseCaseImpl`. */
class BiometricStatusUseCaseApple(
    private val base64Service: Base64Service,
    private val cryptoGenerator: CryptoGenerator,
    private val biometricKeychain: AppleBiometricKeychain,
) : BiometricStatusUseCase {

    private val available = BiometricStatus.Available(
        createCipher = { purpose ->
            LeBiometricCipherApple(
                defer = { cipher, context -> populateCipherWithParams(cipher, purpose, context) },
                forEncryption = purpose is BiometricPurpose.Encrypt,
            )
        },
    )

    override fun invoke(): Flow<BiometricStatus> = AppleBiometricAvailability.revision
        .map { if (hasBiometrics()) available else BiometricStatus.Unavailable }
        .distinctUntilChanged()

    @OptIn(ExperimentalForeignApi::class)
    private fun hasBiometrics(): Boolean = LAContext()
        .canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)

    private suspend fun populateCipherWithParams(
        cipher: LeBiometricCipherApple,
        purpose: BiometricPurpose,
        context: LAContext,
    ) {
        when (purpose) {
            is BiometricPurpose.Encrypt -> {
                // Init cipher in encrypt mode with random iv
                // seed. The user should persist iv for future use.
                cipher._iv = cryptoGenerator.seed(length = 16)

                val key = cryptoGenerator.seed(length = 32)
                val keyBase64 = base64Service.encodeToString(key)
                biometricKeychain.putBiometric(
                    KeychainIds.BIOMETRIC_UNLOCK.value, keyBase64, context,
                )
                cipher._key = key
            }

            is BiometricPurpose.Decrypt -> {
                cipher._iv = purpose.iv.byteArray
                val keyBase64 = biometricKeychain.getBiometric(
                    KeychainIds.BIOMETRIC_UNLOCK.value, context,
                )
                cipher._key = base64Service.decode(keyBase64)
            }
        }
    }
}

/** Refresh capabilities after the user returns from system Settings. */
object AppleBiometricAvailability {
    internal val revision = MutableStateFlow(0L)

    fun refresh() {
        revision.update { it + 1 }
    }
}
