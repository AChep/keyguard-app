package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentStatusService
import com.artemchep.keyguard.common.usecase.GetGpgAgentStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetGpgAgentStatusImpl(
    gpgAgentStatusService: GpgAgentStatusService,
) : GetGpgAgentStatus {
    private val sharedFlow = gpgAgentStatusService.getStatus()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        gpgAgentStatusService = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
