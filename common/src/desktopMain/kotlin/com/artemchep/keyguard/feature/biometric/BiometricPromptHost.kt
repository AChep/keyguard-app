package com.artemchep.keyguard.feature.biometric

import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.platform.LeBiometricCipher

/**
 * Describes the prompt that a platform shows to
 * the user while it verifies the biometrics.
 */
class BiometricPromptRequest(
    val title: String,
    /**
     * Native handle of the window that owns the prompt,
     * `0` if unknown.
     */
    val windowHandle: Long,
)

/**
 * Platform-specific backend of the desktop biometric unlock. An
 * implementation creates the ciphers and verifies the user to populate
 * them with the key material. Removing the platform credential is the
 * job of the [com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository].
 */
interface BiometricPromptHost {
    suspend fun createCipher(purpose: BiometricPurpose): LeBiometricCipher

    /**
     * Verifies the user and populates the cipher with
     * the key material, throwing on failure.
     */
    suspend fun materialize(
        request: BiometricPromptRequest,
        cipher: LeBiometricCipher,
    )
}
