package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverAutoRefresh
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutGpgKeyserverAutoRefreshImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgKeyserverAutoRefresh {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(autoRefresh: Boolean) = settingsReadWriteRepository
        .setGpgKeyserverAutoRefresh(autoRefresh)
}
