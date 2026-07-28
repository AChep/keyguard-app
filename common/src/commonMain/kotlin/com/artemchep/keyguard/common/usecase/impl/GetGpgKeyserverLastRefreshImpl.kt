package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverLastRefresh
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetGpgKeyserverLastRefreshImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgKeyserverLastRefresh {
    private val sharedFlow = settingsReadRepository.getGpgKeyserverLastRefresh()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
