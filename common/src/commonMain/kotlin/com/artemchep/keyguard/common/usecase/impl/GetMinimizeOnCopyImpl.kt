package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetMinimizeOnCopy

class GetMinimizeOnCopyImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetMinimizeOnCopy {
    private val sharedFlow = settingsReadRepository.getMinimizeOnCopy()

    override fun invoke() = sharedFlow
}
