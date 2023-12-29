package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetUseExternalBrowser
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetUseExternalBrowserImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetUseExternalBrowser {
    private val sharedFlow = settingsReadRepository.getUseExternalBrowser()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
