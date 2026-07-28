package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverRefreshInterval
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetGpgKeyserverRefreshIntervalImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgKeyserverRefreshInterval {
    private val sharedFlow = settingsReadRepository.getGpgKeyserverRefreshInterval()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
