package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import kotlinx.coroutines.flow.distinctUntilChanged

class GetCheckTwoFAImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCheckTwoFA {
    private val sharedFlow = settingsReadRepository.getCheckTwoFA()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
