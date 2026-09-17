package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import kotlinx.coroutines.flow.Flow

class GetBiometricRequireConfirmationImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetBiometricRequireConfirmation {
    private val sharedFlow = settingsReadRepository.getBiometricRequireConfirmation()

    override fun invoke(): Flow<Boolean> = sharedFlow
}
