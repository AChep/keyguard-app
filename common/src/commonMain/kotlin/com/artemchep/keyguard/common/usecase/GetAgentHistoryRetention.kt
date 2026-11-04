package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * Emits the age after which the agent request logs are deleted,
 * or [Duration.INFINITE] to keep them forever.
 */
interface GetAgentHistoryRetention : () -> Flow<Duration>
