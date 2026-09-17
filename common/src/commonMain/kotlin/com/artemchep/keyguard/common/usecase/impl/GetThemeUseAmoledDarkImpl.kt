package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetThemeUseAmoledDark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

class GetThemeUseAmoledDarkImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetThemeUseAmoledDark {
    private val sharedFlow = settingsReadRepository.getThemeUseAmoledDark()
        .distinctUntilChanged()

    override fun invoke(): Flow<Boolean> = sharedFlow
}
