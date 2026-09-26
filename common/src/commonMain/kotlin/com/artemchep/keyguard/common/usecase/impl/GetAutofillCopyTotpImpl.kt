package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillCopyTotp
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAutofillCopyTotpImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillCopyTotp {
    private val sharedFlow = settingsReadRepository.getAutofillCopyTotp()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
