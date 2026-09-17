package com.artemchep.keyguard.feature.biometric

import com.artemchep.autotype.biometricsDeleteCredential
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.keychain.KeychainIds
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform

/**
 * Removes the platform credential of the desktop biometric unlock:
 * the Windows Hello protected key, macOS keychain entry, or Linux
 * process-local protected credential.
 */
class BiometricKeyRepositoryDesktop(
    private val keychainRepository: KeychainRepository,
    private val platform: Platform = CurrentPlatform,
) : BiometricKeyRepository {

    override fun delete() = ioEffect {
        when (platform) {
            is Platform.Desktop.Windows -> {
                biometricsDeleteCredential()
            }

            else -> {
                keychainRepository.delete(KeychainIds.BIOMETRIC_UNLOCK.value)
                    .bind()
            }
        }
        Unit
    }

    override fun exists(): IO<Boolean> = when (platform) {
        // The credential belongs to this process and is gone
        // after a restart, see the native keychain backend.
        is Platform.Desktop.Linux ->
            keychainRepository.contains(KeychainIds.BIOMETRIC_UNLOCK.value)

        else -> io(true)
    }
}
