package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetLocale
import kotlinx.coroutines.flow.Flow

class GetLocaleImpl(
    private val settingsReadRepository: SettingsReadRepository,
) : GetLocale {
    override fun invoke(): Flow<String?> = settingsReadRepository
        .getLocale()
}
