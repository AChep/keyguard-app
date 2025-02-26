package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.watchtower.ServicePwnedDisabledException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRepository
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetBreachesImpl(
    private val breachesRepository: BreachesRepository,
    private val getCheckPwnedServices: GetCheckPwnedServices,
) : GetBreaches {
    constructor(directDI: DirectDI) : this(
        breachesRepository = directDI.instance(),
        getCheckPwnedServices = directDI.instance(),
    )

    override fun invoke(forceRefresh: Boolean): IO<HibpBreachGroup> = getCheckPwnedServices()
        .toIO()
        .flatMap { enabled ->
            if (!enabled) {
                val e = ServicePwnedDisabledException()
                return@flatMap ioRaise(e)
            }

            breachesRepository.get(forceRefresh = forceRefresh)
        }
}
