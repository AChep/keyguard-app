package com.artemchep.keyguard.common.service.totp

import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import kotlin.time.Instant

interface TotpService {
    fun generate(
        token: TotpToken,
        timestamp: Instant,
        offset: Int = 0,
    ): TotpCode
}
