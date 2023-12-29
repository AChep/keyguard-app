package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.usecase.GetProducts
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetProductsImpl(
    private val subscriptionService: SubscriptionService,
) : GetProducts {
    constructor(directDI: DirectDI) : this(
        subscriptionService = directDI.instance(),
    )

    override fun invoke() = subscriptionService.products()
}
