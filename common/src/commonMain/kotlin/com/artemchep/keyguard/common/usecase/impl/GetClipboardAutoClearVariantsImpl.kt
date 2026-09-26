package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetClipboardAutoClearVariants
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetClipboardAutoClearVariantsImpl : GetClipboardAutoClearVariants {
    companion object {
        private val DEFAULT_DURATION_VARIANTS
            get() = with(Duration) {
                listOf(
                    10L.seconds,
                    20L.seconds,
                    30L.seconds,
                    1L.minutes,
                    2L.minutes,
                    5L.minutes,
                    INFINITE,
                )
            }
    }

    override fun invoke(): Flow<List<Duration>> = flowOf(DEFAULT_DURATION_VARIANTS)
}
