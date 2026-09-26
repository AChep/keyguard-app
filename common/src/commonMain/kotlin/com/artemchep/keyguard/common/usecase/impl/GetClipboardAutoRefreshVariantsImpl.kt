package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetClipboardAutoRefreshVariants
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetClipboardAutoRefreshVariantsImpl : GetClipboardAutoRefreshVariants {
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

    override fun invoke(): Flow<List<Duration>> = flowOf(DEFAULT_DURATION_VARIANTS)
}
