package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** How long a GPG agent approval prompt waits for the user; infinite never expires. */
interface GetGpgAgentApprovalTimeout : () -> Flow<Duration>
