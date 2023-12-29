package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetClipboardAutoClearVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import kotlin.time.Duration

class GetClipboardAutoClearVariantsImpl() : GetClipboardAutoClearVariants {
    companion object {
        private val DEFAULT_DURATION_VARIANTS
            get() = with(Duration) {
                listOf(
                    5L.minutes,
                    INFINITE,
                )
            }
    }

    constructor(directDI: DirectDI) : this()

    override fun invoke(): Flow<List<Duration>> = flowOf(DEFAULT_DURATION_VARIANTS)
}
