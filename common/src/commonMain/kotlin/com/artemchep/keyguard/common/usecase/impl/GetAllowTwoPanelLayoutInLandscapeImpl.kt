package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInLandscape
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class GetAllowTwoPanelLayoutInLandscapeImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAllowTwoPanelLayoutInLandscape {
    private val sharedFlow = settingsReadRepository.getAllowTwoPanelLayoutInLandscape()
        .stateIn(
            scope = GlobalScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    override fun invoke() = sharedFlow
}
