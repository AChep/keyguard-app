package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetMinimizeOnCopy
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetMinimizeOnCopyImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetMinimizeOnCopy {
    private val sharedFlow = settingsReadRepository.getMinimizeOnCopy()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
