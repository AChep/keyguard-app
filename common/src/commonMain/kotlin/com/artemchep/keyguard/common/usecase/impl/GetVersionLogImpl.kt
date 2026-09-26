package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetVersionLog
import kotlinx.coroutines.flow.distinctUntilChanged

class GetVersionLogImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetVersionLog {
    private val sharedFlow = settingsReadRepository.getAppVersionLog()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
