package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.state.StateRepository
import com.artemchep.keyguard.common.usecase.GetScreenState

class GetScreenStateImpl(
    private val stateRepository: StateRepository,
) : GetScreenState {
    override fun invoke(key: String) = stateRepository.get(key)
}
