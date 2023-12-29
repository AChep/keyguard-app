package com.artemchep.keyguard.common.service.subscription

import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.model.Subscription
import kotlinx.coroutines.flow.Flow

interface SubscriptionService {
    fun purchased(): Flow<RichResult<Boolean>>

    fun subscriptions(): Flow<List<Subscription>?>

    fun products(): Flow<List<Product>?>
}
