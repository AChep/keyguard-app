package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * Emits the age after which generator history entries are deleted,
 * or [Duration.INFINITE] to keep them forever.
 */
interface GetGeneratorHistoryRetention : () -> Flow<Duration>
