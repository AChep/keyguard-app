package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.NavAnimation
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetNavAnimationImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetNavAnimation {
    private val sharedFlow = settingsReadRepository.getNavAnimation()
        .map { it ?: NavAnimation.default }
        .distinctUntilChanged()
        .stateIn(
            scope = GlobalScope,
            started = SharingStarted.Eagerly,
            initialValue = NavAnimation.default,
        )

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
