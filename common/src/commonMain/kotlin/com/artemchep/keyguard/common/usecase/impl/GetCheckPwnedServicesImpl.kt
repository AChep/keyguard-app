package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import kotlinx.coroutines.flow.distinctUntilChanged

class GetCheckPwnedServicesImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCheckPwnedServices {
    private val sharedFlow = settingsReadRepository.getCheckPwnedServices()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
