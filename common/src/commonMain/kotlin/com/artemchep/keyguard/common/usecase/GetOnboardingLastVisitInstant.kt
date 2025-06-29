package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface GetOnboardingLastVisitInstant : () -> Flow<Instant?>
