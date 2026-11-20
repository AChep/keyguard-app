package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AppUpdateDelay
import com.artemchep.keyguard.common.usecase.GetAppUpdateDelayVariants
import kotlinx.coroutines.flow.flowOf

class GetAppUpdateDelayVariantsImpl : GetAppUpdateDelayVariants {
    private val sharedFlow = flowOf(AppUpdateDelay.variants)

    override fun invoke() = sharedFlow
}
