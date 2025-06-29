package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret
import kotlin.time.Instant

interface CipherExpiringCheck : (DSecret, Instant) -> Instant?
