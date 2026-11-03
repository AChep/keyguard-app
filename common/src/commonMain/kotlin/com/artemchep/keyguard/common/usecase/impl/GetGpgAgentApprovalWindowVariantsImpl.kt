package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_WINDOW_NEVER_ASK
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowVariants
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class GetGpgAgentApprovalWindowVariantsImpl : GetGpgAgentApprovalWindowVariants {
    private val sharedFlow = flowOf(
        listOf(
            Duration.ZERO,
            1.minutes,
            5.minutes,
            15.minutes,
            Duration.INFINITE,
            AGENT_APPROVAL_WINDOW_NEVER_ASK,
        ),
    )

    override fun invoke() = sharedFlow
}
