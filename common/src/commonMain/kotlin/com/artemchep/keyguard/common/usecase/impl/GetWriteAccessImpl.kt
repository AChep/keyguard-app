package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import kotlinx.coroutines.flow.distinctUntilChanged

class GetWriteAccessImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetWriteAccess {
    private val sharedFlow = settingsReadRepository.getWriteAccess()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
