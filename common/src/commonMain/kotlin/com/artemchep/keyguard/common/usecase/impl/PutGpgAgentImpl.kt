package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgAgent
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutGpgAgentImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgAgent {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(gpgAgent: Boolean): IO<Unit> = settingsReadWriteRepository
        .setGpgAgent(gpgAgent)
}
