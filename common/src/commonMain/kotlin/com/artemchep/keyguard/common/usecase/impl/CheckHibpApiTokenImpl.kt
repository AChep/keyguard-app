package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.hibp.HibpRepository
import com.artemchep.keyguard.common.usecase.CheckHibpApiToken
import org.kodein.di.DirectDI
import org.kodein.di.instance

class CheckHibpApiTokenImpl(
    private val hibpRepository: HibpRepository,
) : CheckHibpApiToken {
    constructor(directDI: DirectDI) : this(
        hibpRepository = directDI.instance(),
    )

    override fun invoke(token: String): IO<Unit> = hibpRepository
        .getSubscriptionStatus(apiToken = token)
}
