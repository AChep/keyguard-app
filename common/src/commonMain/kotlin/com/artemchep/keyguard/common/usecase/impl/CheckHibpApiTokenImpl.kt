package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.hibp.HibpRepository
import com.artemchep.keyguard.common.usecase.CheckHibpApiToken

class CheckHibpApiTokenImpl(
    private val hibpRepository: HibpRepository,
) : CheckHibpApiToken {
    override fun invoke(token: String): IO<Unit> = hibpRepository
        .getSubscriptionStatus(apiToken = token)
}
