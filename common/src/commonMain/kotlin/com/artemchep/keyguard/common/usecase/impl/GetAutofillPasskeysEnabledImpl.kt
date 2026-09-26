package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillPasskeysEnabled
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAutofillPasskeysEnabledImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillPasskeysEnabled {
    private val sharedFlow = settingsReadRepository.getAdvertisePasskeysSupport()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
