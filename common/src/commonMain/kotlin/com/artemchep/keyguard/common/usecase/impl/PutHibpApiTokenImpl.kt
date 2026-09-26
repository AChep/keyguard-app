package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.VaultSettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutHibpApiToken

class PutHibpApiTokenImpl(
    private val vaultSettingsReadWriteRepository: VaultSettingsReadWriteRepository,
) : PutHibpApiToken {
    override fun invoke(token: String?): IO<Unit> =
        vaultSettingsReadWriteRepository.setHibpApiToken(token)
}
