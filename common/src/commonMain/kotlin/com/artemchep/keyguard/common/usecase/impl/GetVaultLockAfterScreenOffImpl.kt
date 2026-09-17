package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class GetVaultLockAfterScreenOffImpl(
    settingsReadRepository: SettingsReadRepository,
    getVaultPersist: GetVaultPersist,
) : GetVaultLockAfterScreenOff {
    private val sharedFlow = combine(
        getVaultPersist(),
        settingsReadRepository.getVaultScreenLock(),
    ) { persist, lockAfterScreenOff ->
        lockAfterScreenOff && !persist
    }
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
