package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutSshAgent
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutSshAgentImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutSshAgent {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(sshAgent: Boolean): IO<Unit> = settingsReadWriteRepository
        .setSshAgent(sshAgent)
}
