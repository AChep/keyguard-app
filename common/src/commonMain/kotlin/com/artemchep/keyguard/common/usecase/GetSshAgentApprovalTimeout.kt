package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** How long an SSH agent approval prompt waits for the user; infinite never expires. */
interface GetSshAgentApprovalTimeout : () -> Flow<Duration>
