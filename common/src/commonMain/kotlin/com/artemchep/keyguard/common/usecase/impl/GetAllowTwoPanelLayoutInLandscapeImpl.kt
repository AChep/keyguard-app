package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInLandscape
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAllowTwoPanelLayoutInLandscapeImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAllowTwoPanelLayoutInLandscape {
    private val sharedFlow = settingsReadRepository.getAllowTwoPanelLayoutInLandscape()
        .stateIn(
            scope = GlobalScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
