package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverAutoRefresh
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetGpgKeyserverAutoRefreshImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgKeyserverAutoRefresh {
    private val sharedFlow = settingsReadRepository.getGpgKeyserverAutoRefresh()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
