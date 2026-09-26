package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInPortrait
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class GetAllowTwoPanelLayoutInPortraitImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAllowTwoPanelLayoutInPortrait {
    private val sharedFlow = settingsReadRepository.getAllowTwoPanelLayoutInPortrait()
        .stateIn(
            scope = GlobalScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    override fun invoke() = sharedFlow
}
