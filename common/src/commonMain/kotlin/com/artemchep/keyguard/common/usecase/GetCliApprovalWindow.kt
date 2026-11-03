package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** How long a terminal tab keeps a `keyguard` CLI approval; zero asks every time. */
interface GetCliApprovalWindow : () -> Flow<Duration>
