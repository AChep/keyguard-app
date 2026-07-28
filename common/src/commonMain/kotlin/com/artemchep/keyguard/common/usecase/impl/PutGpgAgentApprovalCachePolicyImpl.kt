package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalCachePolicy
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutGpgAgentApprovalCachePolicyImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgAgentApprovalCachePolicy {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(policy: AgentApprovalCachePolicy) = settingsReadWriteRepository
        .setGpgAgentApprovalCachePolicy(policy)
}
