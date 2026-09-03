package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.keychain.KeychainIds
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import org.kodein.di.DirectDI
import org.kodein.di.instance

class BiometricKeyRepositoryApple(
    private val keychainRepository: KeychainRepository,
) : BiometricKeyRepository {
    constructor(directDI: DirectDI) : this(
        keychainRepository = directDI.instance(),
    )

    override fun delete() = keychainRepository
        .delete(KeychainIds.BIOMETRIC_UNLOCK.value)
        .map { Unit }
}
