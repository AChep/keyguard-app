package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

interface GetClipboardAutoRefreshVariants : () -> Flow<List<Duration>>
