package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import kotlinx.datetime.Instant

interface PutOnboardingLastVisitInstant : (Instant) -> IO<Unit>
