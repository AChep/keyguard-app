package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** The choices of how long an agent approval prompt waits for the user. */
interface GetAgentApprovalTimeoutVariants : () -> Flow<List<Duration>>
