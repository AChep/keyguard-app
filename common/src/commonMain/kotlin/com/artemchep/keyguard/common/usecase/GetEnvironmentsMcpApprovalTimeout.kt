package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** How long an MCP or local `.env` file approval prompt waits for the user; infinite never expires. */
interface GetEnvironmentsMcpApprovalTimeout : () -> Flow<Duration>
