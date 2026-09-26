package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBiometricRequireConfirmation

class PutBiometricRequireConfirmationImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBiometricRequireConfirmation {
    override fun invoke(requireConfirmation: Boolean): IO<Unit> = settingsReadWriteRepository
        .setBiometricRequireConfirmation(requireConfirmation)
}
