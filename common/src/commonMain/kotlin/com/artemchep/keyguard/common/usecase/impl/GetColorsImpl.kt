package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AppColors
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetColorsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetColors {
    private val sharedFlow = settingsReadRepository.getColors()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke(): Flow<AppColors?> = sharedFlow
}
