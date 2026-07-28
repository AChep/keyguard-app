package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetSshAgentApprovalCachePolicyImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetSshAgentApprovalCachePolicy {
    override val approvalCacheConfig = settingsReadRepository
        .getSshAgentApprovalCacheConfig()

    private val sharedFlow = settingsReadRepository.getSshAgentApprovalCachePolicy()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
