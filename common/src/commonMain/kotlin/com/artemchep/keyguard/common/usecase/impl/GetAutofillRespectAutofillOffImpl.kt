package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillRespectAutofillOff
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAutofillRespectAutofillOffImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillRespectAutofillOff {
    private val sharedFlow = settingsReadRepository.getAutofillRespectAutofillOff()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
