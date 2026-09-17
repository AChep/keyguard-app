package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import kotlinx.coroutines.flow.distinctUntilChanged

class GetCheckPasskeysImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCheckPasskeys {
    private val sharedFlow = settingsReadRepository.getCheckPasskeys()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
