package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.totp.TotpService
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetTotpCodeWithOffsetImpl(
    private val totpService: TotpService,
) : GetTotpCodeWithOffset {
    constructor(directDI: DirectDI) : this(
        totpService = directDI.instance(),
    )

    override fun invoke(
        token: TotpToken,
        offset: Int,
    ): Flow<TotpCode> = flow {
        while (true) {
            val now = Clock.System.now()

            val zeroCode = totpService.generate(
                token = token,
                timestamp = now,
                offset = 0,
            )
            val code = if (offset != 0) {
                totpService.generate(
                    token = token,
                    timestamp = now,
                    offset = offset,
                )
            } else {
                zeroCode
            }
            emit(code)
            // We want to refresh the code based on the zero-code result, because
            // we do not know when the one with an offset will change.
            when (val counter = zeroCode.counter) {
                is TotpCode.TimeBasedCounter -> {
                    // Wait for the code to expire, and then
                    // regenerate it.
                    do {
                        val dt = counter.expiration - Clock.System.now()
                        delay(dt)
                    } while (dt.isPositive())
                }

                is TotpCode.IncrementBasedCounter -> {
                    break
                }
            }
        }
    }
}
