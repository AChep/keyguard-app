package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetVaultLockAfterRebootImpl(
    settingsReadRepository: SettingsReadRepository,
    getVaultPersist: GetVaultPersist,
) : GetVaultLockAfterReboot {
    private val sharedFlow = combine(
        getVaultPersist(),
        settingsReadRepository.getVaultLockAfterReboot(),
    ) { persist, lockAfterReboot ->
        lockAfterReboot || !persist
    }
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
        getVaultPersist = directDI.instance(),
    )

    override fun invoke(): Flow<Boolean> = sharedFlow
}
