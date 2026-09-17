package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.usecase.PutSshAgentDisplayKeyNames

class PutSshAgentDisplayKeyNamesImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
    private val sshAgentPublicKeyRepository: SshAgentPublicKeyRepository,
) : PutSshAgentDisplayKeyNames {
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
