package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

interface GetGpgAgentApprovalWindow : () -> Flow<Duration>

object GetGpgAgentApprovalWindowNoOp : GetGpgAgentApprovalWindow {
    override fun invoke(): Flow<Duration> = kotlinx.coroutines.flow.flowOf(Duration.ZERO)
}
