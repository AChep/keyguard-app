package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy

class GetGpgAgentApprovalCachePolicyImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgAgentApprovalCachePolicy {
    override val approvalCacheConfig = settingsReadRepository
        .getGpgAgentApprovalCacheConfig()

    private val sharedFlow = settingsReadRepository.getGpgAgentApprovalCachePolicy()

    override fun invoke() = sharedFlow
}
