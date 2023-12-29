package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetKeepScreenOnImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetKeepScreenOn {
    private val sharedFlow = settingsReadRepository.getKeepScreenOn()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
