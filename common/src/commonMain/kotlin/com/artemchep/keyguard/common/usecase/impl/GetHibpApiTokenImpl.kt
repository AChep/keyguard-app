package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.VaultSettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetHibpApiToken
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetHibpApiTokenImpl(
    vaultSettingsReadRepository: VaultSettingsReadRepository,
) : GetHibpApiToken {
    private val sharedFlow = vaultSettingsReadRepository.getHibpApiToken()

    constructor(directDI: DirectDI) : this(
        vaultSettingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
