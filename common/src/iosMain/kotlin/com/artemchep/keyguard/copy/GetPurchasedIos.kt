package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.GetPurchased
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object GetPurchasedIos : GetPurchased {
    override fun invoke(): Flow<Boolean> = flowOf(true)
}
