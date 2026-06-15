package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetNavForceHiddenSend
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetNavForceHiddenSendImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetNavForceHiddenSend {
    private val sharedFlow = settingsReadRepository.getNavHiddenSend()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
