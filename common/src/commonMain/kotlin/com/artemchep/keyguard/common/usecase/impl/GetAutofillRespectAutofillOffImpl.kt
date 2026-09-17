package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillRespectAutofillOff
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAutofillRespectAutofillOffImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillRespectAutofillOff {
    private val sharedFlow = settingsReadRepository.getAutofillRespectAutofillOff()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
