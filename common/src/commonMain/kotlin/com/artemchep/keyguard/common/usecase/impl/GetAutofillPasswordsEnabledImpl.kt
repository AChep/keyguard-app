package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.GetAutofillPasswordsEnabled
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAutofillPasswordsEnabledImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillPasswordsEnabled {
    private val sharedFlow = settingsReadRepository.getAdvertisePasswordsSupport()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
