package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetSshAgentApprovalWindowImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetSshAgentApprovalWindow {
    private val sharedFlow = settingsReadRepository.getSshAgentApprovalWindow()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
