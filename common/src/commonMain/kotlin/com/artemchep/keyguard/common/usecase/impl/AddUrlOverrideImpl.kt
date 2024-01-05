package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepository
import com.artemchep.keyguard.common.service.urloverride.UrlOverrideRepository
import com.artemchep.keyguard.common.usecase.AddEmailRelay
import com.artemchep.keyguard.common.usecase.AddUrlOverride
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddUrlOverrideImpl(
    private val urlOverrideRepository: UrlOverrideRepository,
) : AddUrlOverride {
    constructor(directDI: DirectDI) : this(
        urlOverrideRepository = directDI.instance(),
    )

    override fun invoke(model: DGlobalUrlOverride) = urlOverrideRepository
        .put(model)
}
