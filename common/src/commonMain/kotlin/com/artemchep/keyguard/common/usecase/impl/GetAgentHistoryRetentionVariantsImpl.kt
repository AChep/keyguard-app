package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AgentHistoryRetention
import com.artemchep.keyguard.common.usecase.GetAgentHistoryRetentionVariants
import kotlinx.coroutines.flow.flowOf

class GetAgentHistoryRetentionVariantsImpl : GetAgentHistoryRetentionVariants {
    private val sharedFlow = flowOf(AgentHistoryRetention.variants)

    override fun invoke() = sharedFlow
}
