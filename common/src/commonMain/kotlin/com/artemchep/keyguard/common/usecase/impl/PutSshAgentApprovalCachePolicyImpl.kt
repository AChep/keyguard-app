package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalCachePolicy
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutSshAgentApprovalCachePolicyImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutSshAgentApprovalCachePolicy {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(policy: AgentApprovalCachePolicy) = settingsReadWriteRepository
        .setSshAgentApprovalCachePolicy(policy)
}
