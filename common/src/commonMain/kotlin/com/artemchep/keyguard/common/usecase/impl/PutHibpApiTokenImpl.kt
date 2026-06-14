package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.VaultSettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutHibpApiToken
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutHibpApiTokenImpl(
    private val vaultSettingsReadWriteRepository: VaultSettingsReadWriteRepository,
) : PutHibpApiToken {
    constructor(directDI: DirectDI) : this(
        vaultSettingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(token: String?): IO<Unit> =
        vaultSettingsReadWriteRepository.setHibpApiToken(token)
}
