package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_WINDOW_NEVER_ASK
import com.artemchep.keyguard.common.usecase.GetEnvironmentsApprovalWindowVariants
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class GetEnvironmentsApprovalWindowVariantsImpl : GetEnvironmentsApprovalWindowVariants {
    private val sharedFlow = flowOf(
        listOf(
            Duration.ZERO,
            5.minutes,
            15.minutes,
            1.hours,
            4.hours,
            Duration.INFINITE,
            AGENT_APPROVAL_WINDOW_NEVER_ASK,
        ),
    )

    override fun invoke() = sharedFlow
}
