package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** How long a browser extension approval prompt waits for the user; infinite never expires. */
interface GetBrowserExtensionApprovalTimeout : () -> Flow<Duration>
