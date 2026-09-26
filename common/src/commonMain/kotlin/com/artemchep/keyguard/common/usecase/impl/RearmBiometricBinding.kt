package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.usecase.BiometricKeyEncryptUseCase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Re-arm an existing binding only after its master key has been authenticated. */
internal suspend fun rearmBiometricBinding(
    tokens: Fingerprint,
    masterKey: MasterKey,
    biometric: BiometricStatus,
    biometricKeyRepository: BiometricKeyRepository,
    keyReadWriteRepository: FingerprintReadWriteRepository,
    biometricKeyEncryptUseCase: BiometricKeyEncryptUseCase,
): Boolean {
    if (
        tokens.biometric == null ||
        biometric !is BiometricStatus.Available ||
        biometricKeyRepository.exists().bind()
    ) {
        return false
    }
    var saved = false
    try {
        val cipher = biometric.createCipher(BiometricPurpose.Encrypt)
        val encryptedMasterKey = biometricKeyEncryptUseCase(io(cipher), masterKey).bind()
        val binding = FingerprintBiometric(
            iv = cipher.iv,
            encryptedMasterKey = encryptedMasterKey,
        )
        keyReadWriteRepository.put(tokens.copy(biometric = binding)).bind()
        saved = true
        return true
    } finally {
        if (!saved) {
            // A failed write must not leave a native key paired with the old
            // persisted handle: exists() would otherwise prevent the next retry.
            withContext(NonCancellable) {
                biometricKeyRepository.delete().attempt().bind()
            }
        }
    }
}
