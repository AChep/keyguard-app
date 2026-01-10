package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillPasskeysEnabled
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAutofillPasskeysEnabledImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillPasskeysEnabled {
    private val sharedFlow = settingsReadRepository.getAdvertisePasskeysSupport()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
