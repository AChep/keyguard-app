package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.VaultSettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetHibpApiToken

class GetHibpApiTokenImpl(
    vaultSettingsReadRepository: VaultSettingsReadRepository,
) : GetHibpApiToken {
    private val sharedFlow = vaultSettingsReadRepository.getHibpApiToken()

    override fun invoke() = sharedFlow
}
