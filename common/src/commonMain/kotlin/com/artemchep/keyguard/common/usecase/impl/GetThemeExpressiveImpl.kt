package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetThemeExpressive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetThemeExpressiveImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetThemeExpressive {
    private val sharedFlow = settingsReadRepository.getThemeM3Expressive()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke(): Flow<Boolean> = sharedFlow
}
