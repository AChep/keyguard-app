package com.artemchep.keyguard.feature.biometric

import arrow.core.partially2
import com.artemchep.autotype.biometricsVerify
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.keychain.KeychainIds
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.platform.LeBiometricCipher
import com.artemchep.keyguard.platform.LeBiometricCipherKeychain
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * Verifies the user with the platform biometrics and then loads
 * the cipher key from the login keychain. Used on macOS.
 */
class BiometricPromptHostKeychain(
    private val base64Service: Base64Service,
    private val cryptoGenerator: CryptoGenerator,
    private val keychainRepository: KeychainRepository,
) : BiometricPromptHost {
    constructor(directDI: DirectDI) : this(
        base64Service = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        keychainRepository = directDI.instance(),
    )

    override suspend fun createCipher(purpose: BiometricPurpose): LeBiometricCipher =
        LeBiometricCipherKeychain(
            defer = ::populateCipherWithParams
                .partially2(purpose),
            forEncryption = purpose is BiometricPurpose.Encrypt,
        )

    override suspend fun materialize(
        request: BiometricPromptRequest,
        cipher: LeBiometricCipher,
    ) {
        val keychainCipher = cipher as? LeBiometricCipherKeychain
            ?: error("Unsupported desktop biometric cipher.")
        biometricsVerify(
            windowHandle = request.windowHandle,
            title = request.title,
        )
        keychainCipher.materialize()
    }

    private fun populateCipherWithParams(
        cipher: LeBiometricCipherKeychain,
        purpose: BiometricPurpose,
    ) {
        when (purpose) {
            is BiometricPurpose.Encrypt -> {
                // Init cipher in encrypt mode with random iv
                // seed. The user should persist iv for future use.
                cipher._iv = cryptoGenerator.seed(length = 16)

                val key = cryptoGenerator.seed(length = 32)
                val keyBase64 = base64Service.encodeToString(key)
                // Save the key in the login keychain.
                keychainRepository.put(KeychainIds.BIOMETRIC_UNLOCK.value, keyBase64)
                    .bindBlocking()
                cipher._key = key
            }

            is BiometricPurpose.Decrypt -> {
                cipher._iv = purpose.iv.byteArray
                // Obtain the cipher key from the
                // login keychain.
                val keyBase64 = keychainRepository.get(KeychainIds.BIOMETRIC_UNLOCK.value)
                    .bindBlocking()
                cipher._key = base64Service.decode(keyBase64)
            }
        }
    }
}
