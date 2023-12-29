package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutofillCopyTotp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAutofillCopyTotpImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutofillCopyTotp {
    private val sharedFlow = settingsReadRepository.getAutofillCopyTotp()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
