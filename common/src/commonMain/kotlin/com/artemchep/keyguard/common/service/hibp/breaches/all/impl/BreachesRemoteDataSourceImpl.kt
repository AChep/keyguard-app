package com.artemchep.keyguard.common.service.hibp.breaches.all.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.hibp.HibpRepository
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRemoteDataSource
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup

class BreachesRemoteDataSourceImpl(
    private val hibpRepository: HibpRepository,
) : BreachesRemoteDataSource {
    override fun get(): IO<HibpBreachGroup> = hibpRepository.getBreaches()
}
