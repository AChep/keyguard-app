package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutSshAgentFilter
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutSshAgentFilterImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutSshAgentFilter {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(filter: SshAgentFilter): IO<Unit> = settingsReadWriteRepository
        .setSshAgentFilter(filter.normalize())
}

