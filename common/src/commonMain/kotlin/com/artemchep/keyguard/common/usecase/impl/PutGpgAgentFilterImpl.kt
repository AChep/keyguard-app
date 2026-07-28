package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgAgentFilter
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutGpgAgentFilterImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgAgentFilter {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(filter: GpgAgentFilter): IO<Unit> = settingsReadWriteRepository
        .setGpgAgentFilter(filter.normalize())
}
