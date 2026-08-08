package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgAgentDisplayKeyNames
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutGpgAgentDisplayKeyNamesImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
    private val gpgPublicKeyRepository: GpgPublicKeyRepository,
) : PutGpgAgentDisplayKeyNames {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
        gpgPublicKeyRepository = directDI.instance(),
    )

    override fun invoke(displayKeyNames: Boolean): IO<Unit> = {
        settingsReadWriteRepository
            .setGpgAgentDisplayKeyNames(displayKeyNames)
            .bind()
        if (!displayKeyNames) {
            gpgPublicKeyRepository.clearNames()
                .bind()
        }
    }
}
