package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.usecase.PutSshAgentDisplayKeyNames
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutSshAgentDisplayKeyNamesImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
    private val sshAgentPublicKeyRepository: SshAgentPublicKeyRepository,
) : PutSshAgentDisplayKeyNames {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
        sshAgentPublicKeyRepository = directDI.instance(),
    )

    override fun invoke(displayKeyNames: Boolean): IO<Unit> = {
        settingsReadWriteRepository
            .setSshAgentDisplayKeyNames(displayKeyNames)
            .invoke()
        if (!displayKeyNames) {
            sshAgentPublicKeyRepository.clearNames()
                .invoke()
        }
    }
}
