package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetBiometricRequireConfirmationImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetBiometricRequireConfirmation {
    private val sharedFlow = settingsReadRepository.getBiometricRequireConfirmation()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke(): Flow<Boolean> = sharedFlow
}
