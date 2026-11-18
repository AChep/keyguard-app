package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.HistoryRetention
import com.artemchep.keyguard.common.usecase.GetHistoryRetentionVariants
import kotlinx.coroutines.flow.flowOf

class GetHistoryRetentionVariantsImpl : GetHistoryRetentionVariants {
    private val sharedFlow = flowOf(HistoryRetention.variants)

    override fun invoke() = sharedFlow
}
