package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetClipboardAutoRefreshVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import kotlin.time.Duration

class GetClipboardAutoRefreshVariantsImpl() : GetClipboardAutoRefreshVariants {
    companion object {
        private val DEFAULT_DURATION_VARIANTS
            get() = with(Duration) {
                listOf(
                    ZERO,
                    30L.seconds,
                    1L.minutes,
                    2L.minutes,
                )
            }
    }

    constructor(directDI: DirectDI) : this()

    override fun invoke(): Flow<List<Duration>> = flowOf(DEFAULT_DURATION_VARIANTS)
}
