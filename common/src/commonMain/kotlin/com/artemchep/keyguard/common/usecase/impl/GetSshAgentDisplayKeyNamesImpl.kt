package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentDisplayKeyNames
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetSshAgentDisplayKeyNamesImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetSshAgentDisplayKeyNames {
    private val sharedFlow = settingsReadRepository.getSshAgentDisplayKeyNames()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
