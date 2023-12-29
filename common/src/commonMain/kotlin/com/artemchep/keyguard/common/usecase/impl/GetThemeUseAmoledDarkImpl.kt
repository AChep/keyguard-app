package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetThemeUseAmoledDark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetThemeUseAmoledDarkImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetThemeUseAmoledDark {
    private val sharedFlow = settingsReadRepository.getThemeUseAmoledDark()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke(): Flow<Boolean> = sharedFlow
}
