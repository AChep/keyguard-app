package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** How long a `keyguard` CLI approval prompt waits for the user; infinite never expires. */
interface GetCliApprovalTimeout : () -> Flow<Duration>
