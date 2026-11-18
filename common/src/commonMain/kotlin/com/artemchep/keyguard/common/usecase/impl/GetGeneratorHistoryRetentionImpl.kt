package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGeneratorHistoryRetention
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGeneratorHistoryRetentionImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGeneratorHistoryRetention {
    private val sharedFlow = settingsReadRepository.getGeneratorHistoryRetention()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
