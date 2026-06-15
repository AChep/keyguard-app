package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.GetAutofillPasswordsEnabled
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAutofillPasswordsEnabledImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillPasswordsEnabled {
    private val sharedFlow = settingsReadRepository.getAdvertisePasswordsSupport()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
