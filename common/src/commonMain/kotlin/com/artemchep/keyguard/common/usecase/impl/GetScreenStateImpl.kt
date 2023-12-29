package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.state.StateRepository
import com.artemchep.keyguard.common.usecase.GetScreenState
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetScreenStateImpl(
    private val stateRepository: StateRepository,
) : GetScreenState {
    constructor(directDI: DirectDI) : this(
        stateRepository = directDI.instance(),
    )

    override fun invoke(key: String) = stateRepository.get(key)
}
