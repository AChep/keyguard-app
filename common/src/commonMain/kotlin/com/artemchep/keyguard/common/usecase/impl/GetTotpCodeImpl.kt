package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.totp.TotpService
import com.artemchep.keyguard.common.usecase.GetTotpCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.ExperimentalTime

class GetTotpCodeImpl(
    private val totpService: TotpService,
) : GetTotpCode {
    constructor(directDI: DirectDI) : this(
        totpService = directDI.instance(),
    )

    override fun invoke(
        token: TotpToken,
    ): Flow<TotpCode> = flow {
        while (true) {
            val now = Clock.System.now()
            val code = totpService.generate(
                token = token,
                timestamp = now,
            )
            emit(code)
            when (val counter = code.counter) {
                is TotpCode.TimeBasedCounter -> {
                    // Wait for the code to expire, and then
                    // regenerate it.
                    val dt = counter.expiration - Clock.System.now()
                    delay(dt)
                }

                is TotpCode.IncrementBasedCounter -> {
                    break
                }
            }
        }
    }
}
