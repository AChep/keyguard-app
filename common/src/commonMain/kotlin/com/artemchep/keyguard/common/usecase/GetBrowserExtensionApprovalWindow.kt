package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** How long a paired browser keeps an approval to fill an item on a site; zero asks every time. */
interface GetBrowserExtensionApprovalWindow : () -> Flow<Duration>
