package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import kotlinx.coroutines.flow.Flow

class GetVaultPersistImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetVaultPersist {
    private val sharedFlow = settingsReadRepository
        .getVaultPersist()

    override fun invoke(): Flow<Boolean> = sharedFlow
}
