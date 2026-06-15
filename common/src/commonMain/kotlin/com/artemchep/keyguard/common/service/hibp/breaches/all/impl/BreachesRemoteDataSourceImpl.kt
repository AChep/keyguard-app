package com.artemchep.keyguard.common.service.hibp.breaches.all.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.hibp.HibpRepository
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRemoteDataSource
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import org.kodein.di.DirectDI
import org.kodein.di.instance

class BreachesRemoteDataSourceImpl(
    private val hibpRepository: HibpRepository,
) : BreachesRemoteDataSource {
    constructor(directDI: DirectDI) : this(
        hibpRepository = directDI.instance(),
    )

    override fun get(): IO<HibpBreachGroup> = hibpRepository.getBreaches()
}
