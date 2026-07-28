package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetGpgAgentApprovalCachePolicyImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgAgentApprovalCachePolicy {
    override val approvalCacheConfig = settingsReadRepository
        .getGpgAgentApprovalCacheConfig()

    private val sharedFlow = settingsReadRepository.getGpgAgentApprovalCachePolicy()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
