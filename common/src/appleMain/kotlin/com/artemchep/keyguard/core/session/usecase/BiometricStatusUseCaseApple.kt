package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.keychain.KeychainIds
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.BiometricStatusUseCase
import com.artemchep.keyguard.platform.LeBiometricCipherApple
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.kodein.di.DirectDI
import org.kodein.di.instance
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

/**
 * Apple implementation of [BiometricStatusUseCase], mirroring the desktop
 * `BiometricStatusUseCaseImpl`: available when the device can evaluate the
 * biometrics policy (Touch ID on Macs), with the cipher's AES key stored in
 * the keychain. The biometric gate itself is the system Touch ID sheet
 * evaluated by the caller before the cipher is materialized.
 */
class BiometricStatusUseCaseApple(
    private val base64Service: Base64Service,
    private val cryptoGenerator: CryptoGenerator,
    private val keychainRepository: KeychainRepository,
) : BiometricStatusUseCase {
    constructor(directDI: DirectDI) : this(
        base64Service = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        keychainRepository = directDI.instance(),
    )

    override fun invoke(): Flow<BiometricStatus> = flow {
        val event = if (hasBiometrics()) {
            BiometricStatus.Available(
                createCipher = { purpose ->
                    LeBiometricCipherApple(
                        defer = { cipher -> populateCipherWithParams(cipher, purpose) },
                        forEncryption = purpose is BiometricPurpose.Encrypt,
                    )
                },
                deleteCipher = {
                    keychainRepository.delete(KeychainIds.BIOMETRIC_UNLOCK.value)
                        .bind()
                },
            )
        } else {
            BiometricStatus.Unavailable
        }
        emit(event)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun hasBiometrics(): Boolean = LAContext()
        .canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)

    private suspend fun populateCipherWithParams(
        cipher: LeBiometricCipherApple,
        purpose: BiometricPurpose,
    ) {
        when (purpose) {
            is BiometricPurpose.Encrypt -> {
                // Init cipher in encrypt mode with random iv
                // seed. The user should persist iv for future use.
                cipher._iv = cryptoGenerator.seed(length = 16)

                val key = cryptoGenerator.seed(length = 32)
                val keyBase64 = base64Service.encodeToString(key)
                // Save the key in the keychain.
                keychainRepository.put(KeychainIds.BIOMETRIC_UNLOCK.value, keyBase64)
                    .bind()
                cipher._key = key
            }

            is BiometricPurpose.Decrypt -> {
                cipher._iv = purpose.iv.byteArray
                // Obtain the cipher key from the
                // keychain.
                val keyBase64 = keychainRepository.get(KeychainIds.BIOMETRIC_UNLOCK.value)
                    .bind()
                cipher._key = base64Service.decode(keyBase64)
            }
        }
    }
}
