package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy

class GetSshAgentApprovalCachePolicyImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetSshAgentApprovalCachePolicy {
    override val approvalCacheConfig = settingsReadRepository
        .getSshAgentApprovalCacheConfig()

    private val sharedFlow = settingsReadRepository.getSshAgentApprovalCachePolicy()

    override fun invoke() = sharedFlow
}
