package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.usecase.GetSubscriptions
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetSubscriptionsImpl(
    private val subscriptionService: SubscriptionService,
) : GetSubscriptions {
    constructor(directDI: DirectDI) : this(
        subscriptionService = directDI.instance(),
    )

    override fun invoke() = subscriptionService.subscriptions()
}
