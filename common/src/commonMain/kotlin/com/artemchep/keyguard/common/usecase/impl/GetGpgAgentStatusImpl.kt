package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentStatusService
import com.artemchep.keyguard.common.usecase.GetGpgAgentStatus
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGpgAgentStatusImpl(
    gpgAgentStatusService: GpgAgentStatusService,
) : GetGpgAgentStatus {
    private val sharedFlow = gpgAgentStatusService.getStatus()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
