package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.state.StateRepository
import com.artemchep.keyguard.common.usecase.PutScreenState
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutScreenStateImpl(
    private val stateRepository: StateRepository,
) : PutScreenState {
    constructor(directDI: DirectDI) : this(
        stateRepository = directDI.instance(),
    )

    override fun invoke(key: String, state: Map<String, Any?>): IO<Unit> = stateRepository
        .put(key, state)
}
