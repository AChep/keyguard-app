package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetGpgAgentApprovalWindowImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgAgentApprovalWindow {
    private val sharedFlow = settingsReadRepository.getGpgAgentApprovalWindow()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
