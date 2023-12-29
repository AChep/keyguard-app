package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetVaultPersistImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetVaultPersist {
    private val sharedFlow = settingsReadRepository
        .getVaultPersist()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke(): Flow<Boolean> = sharedFlow
}
