package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetGpgKeyserverRefreshIntervalVariants
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.flowOf

class GetGpgKeyserverRefreshIntervalVariantsImpl : GetGpgKeyserverRefreshIntervalVariants {
    private val sharedFlow = flowOf(
        listOf(
            1.days,
            3.days,
            7.days,
            14.days,
            30.days,
        ),
    )

    override fun invoke() = sharedFlow
}
