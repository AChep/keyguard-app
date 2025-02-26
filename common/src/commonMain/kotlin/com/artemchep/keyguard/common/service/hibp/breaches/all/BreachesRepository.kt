package com.artemchep.keyguard.common.service.hibp.breaches.all

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup

interface BreachesRepository {
    fun get(
        forceRefresh: Boolean = false,
    ): IO<HibpBreachGroup>
}
