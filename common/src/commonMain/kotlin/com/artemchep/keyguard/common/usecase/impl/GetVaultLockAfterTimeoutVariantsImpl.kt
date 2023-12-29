package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeoutVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import kotlin.time.Duration

class GetVaultLockAfterTimeoutVariantsImpl() : GetVaultLockAfterTimeoutVariants {
    companion object {
        private val DEFAULT_DURATION_VARIANTS
            get() = with(Duration) {
                listOf(
                    ZERO,
                    1L.minutes,
                    5L.minutes,
                    15L.minutes,
                    30L.minutes,
                    1L.hours,
                    4L.hours,
                    INFINITE,
                )
            }
    }

    constructor(directDI: DirectDI) : this()

    override fun invoke(): Flow<List<Duration>> = flowOf(DEFAULT_DURATION_VARIANTS)
}
