package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

class GetThemeImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetTheme {
    private val sharedFlow = settingsReadRepository.getTheme()
        .distinctUntilChanged()

    override fun invoke(): Flow<AppTheme?> = sharedFlow
}
