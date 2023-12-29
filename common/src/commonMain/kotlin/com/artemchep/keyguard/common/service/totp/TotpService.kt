package com.artemchep.keyguard.common.service.totp

import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import kotlinx.datetime.Instant

interface TotpService {
    fun generate(
        token: TotpToken,
        timestamp: Instant,
    ): TotpCode
}
