package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutSshAgent

class PutSshAgentImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutSshAgent {
    override fun invoke(sshAgent: Boolean): IO<Unit> = settingsReadWriteRepository
        .setSshAgent(sshAgent)
}
