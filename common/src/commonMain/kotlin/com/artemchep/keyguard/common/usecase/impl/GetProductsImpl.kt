package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.usecase.GetProducts

class GetProductsImpl(
    private val subscriptionService: SubscriptionService,
) : GetProducts {
    override fun invoke() = subscriptionService.products()
}
