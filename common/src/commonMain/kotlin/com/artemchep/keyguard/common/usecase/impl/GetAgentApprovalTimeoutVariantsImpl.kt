package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetAgentApprovalTimeoutVariants
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetAgentApprovalTimeoutVariantsImpl : GetAgentApprovalTimeoutVariants {
    companion object {
        private val DEFAULT_DURATION_VARIANTS
            get() = with(Duration) {
                listOf(
                    30L.seconds,
                    60L.seconds,
                    5L.minutes,
                    30L.minutes,
                    INFINITE,
                )
            }
    }

    override fun invoke(): Flow<List<Duration>> = flowOf(DEFAULT_DURATION_VARIANTS)
}
