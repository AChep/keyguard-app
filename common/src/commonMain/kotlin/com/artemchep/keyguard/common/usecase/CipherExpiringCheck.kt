package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret
import kotlinx.datetime.Instant

interface CipherExpiringCheck : (DSecret, Instant) -> Instant?
