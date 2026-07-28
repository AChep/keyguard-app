package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetGpgKeyserverRefreshIntervalVariants
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import kotlin.time.Duration.Companion.days

class GetGpgKeyserverRefreshIntervalVariantsImpl(
) : GetGpgKeyserverRefreshIntervalVariants {
    private val sharedFlow = flowOf(
        listOf(
            1.days,
            3.days,
            7.days,
            14.days,
            30.days,
        ),
    )

    constructor(directDI: DirectDI) : this()

    override fun invoke() = sharedFlow
}
