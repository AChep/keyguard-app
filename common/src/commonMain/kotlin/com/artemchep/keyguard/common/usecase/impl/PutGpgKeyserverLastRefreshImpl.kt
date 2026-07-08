package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverLastRefresh
import kotlin.time.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutGpgKeyserverLastRefreshImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgKeyserverLastRefresh {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(instant: Instant?): IO<Unit> = settingsReadWriteRepository
        .setGpgKeyserverLastRefresh(instant)
}
