package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** How long an Environments MCP or local .env file approval is remembered; zero asks every time. */
interface GetEnvironmentsMcpApprovalWindow : () -> Flow<Duration>
