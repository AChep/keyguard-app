package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepository
import com.artemchep.keyguard.common.usecase.AddEmailRelay

class AddEmailRelayImpl(
    private val generatorEmailRelayRepository: GeneratorEmailRelayRepository,
) : AddEmailRelay {
    override fun invoke(model: DGeneratorEmailRelay) = generatorEmailRelayRepository
        .put(model)
}
