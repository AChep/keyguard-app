package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgAgent

class PutGpgAgentImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgAgent {
    override fun invoke(gpgAgent: Boolean): IO<Unit> = settingsReadWriteRepository
        .setGpgAgent(gpgAgent)
}
