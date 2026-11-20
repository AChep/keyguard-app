package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * Emits how long a release must be public before
 * the app tells the user about it.
 */
interface GetAppUpdateDelay : () -> Flow<Duration>
