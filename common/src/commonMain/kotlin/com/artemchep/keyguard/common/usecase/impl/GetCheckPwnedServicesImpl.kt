package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetCheckPwnedServicesImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCheckPwnedServices {
    private val sharedFlow = settingsReadRepository.getCheckPwnedServices()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
