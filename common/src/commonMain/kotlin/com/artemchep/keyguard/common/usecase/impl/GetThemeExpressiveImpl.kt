package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetThemeExpressive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

class GetThemeExpressiveImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetThemeExpressive {
    private val sharedFlow = settingsReadRepository.getThemeM3Expressive()
        .distinctUntilChanged()

    override fun invoke(): Flow<Boolean> = sharedFlow
}
