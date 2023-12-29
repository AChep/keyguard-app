package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInPortrait
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAllowTwoPanelLayoutInPortraitImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAllowTwoPanelLayoutInPortrait {
    private val sharedFlow = settingsReadRepository.getAllowTwoPanelLayoutInPortrait()
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
