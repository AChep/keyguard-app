package com.artemchep.keyguard.feature.biometric

import com.artemchep.autotype.biometricsDeleteCredential
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.keychain.KeychainIds
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * Removes the platform credential of the desktop biometric unlock:
 * the Windows Hello protected key on Windows, the login keychain
 * entry everywhere else.
 */
class BiometricKeyRepositoryDesktop(
    private val keychainRepository: KeychainRepository,
    private val platform: Platform = CurrentPlatform,
) : BiometricKeyRepository {
    constructor(directDI: DirectDI) : this(
        keychainRepository = directDI.instance(),
    )

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
}
