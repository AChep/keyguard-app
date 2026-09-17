package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.usecase.GetSubscriptions

class GetSubscriptionsImpl(
    private val subscriptionService: SubscriptionService,
) : GetSubscriptions {
    override fun invoke() = subscriptionService.subscriptions()
}
