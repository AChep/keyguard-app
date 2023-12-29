package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepository
import com.artemchep.keyguard.common.usecase.AddEmailRelay
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddEmailRelayImpl(
    private val generatorEmailRelayRepository: GeneratorEmailRelayRepository,
) : AddEmailRelay {
    constructor(directDI: DirectDI) : this(
        generatorEmailRelayRepository = directDI.instance(),
    )

    override fun invoke(model: DGeneratorEmailRelay) = generatorEmailRelayRepository
        .put(model)
}
