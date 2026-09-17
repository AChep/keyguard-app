package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetBiometricTimeoutVariants
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetBiometricTimeoutVariantsImpl : GetBiometricTimeoutVariants {
    companion object {
        private val DEFAULT_DURATION_VARIANTS
            get() = with(Duration) {
                listOf(
                    ZERO,
                    8L.hours,
                    1L.days,
                    7L.days,
                    14L.days,
                    INFINITE,
                )
            }
    }

    override fun invoke(): Flow<List<Duration>> = flowOf(DEFAULT_DURATION_VARIANTS)
}
