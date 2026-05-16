package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration

interface GetSshAgentApprovalWindow : () -> Flow<Duration>

object GetSshAgentApprovalWindowNoOp : GetSshAgentApprovalWindow {
    override fun invoke() = flowOf(Duration.ZERO)
}
