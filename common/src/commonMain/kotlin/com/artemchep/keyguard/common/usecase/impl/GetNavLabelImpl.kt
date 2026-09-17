package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetNavLabel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class GetNavLabelImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetNavLabel {
    private val sharedFlow = settingsReadRepository.getNavLabel()
        .stateIn(
            scope = GlobalScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    override fun invoke() = sharedFlow
}
