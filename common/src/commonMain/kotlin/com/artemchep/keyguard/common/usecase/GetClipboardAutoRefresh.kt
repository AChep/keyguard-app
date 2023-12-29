package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

interface GetClipboardAutoRefresh : () -> Flow<Duration>
