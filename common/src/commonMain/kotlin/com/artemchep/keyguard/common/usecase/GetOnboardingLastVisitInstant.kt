package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

interface GetOnboardingLastVisitInstant : () -> Flow<Instant?>
