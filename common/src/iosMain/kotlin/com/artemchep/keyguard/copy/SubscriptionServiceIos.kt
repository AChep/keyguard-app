package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object SubscriptionServiceIos : SubscriptionService {
    override fun purchased(): Flow<RichResult<Boolean>> =
        flowOf(RichResult.Success(false))

    override fun subscriptions(): Flow<List<Subscription>?> =
        flowOf(emptyList())

    override fun products(): Flow<List<Product>?> =
        flowOf(emptyList())
}
