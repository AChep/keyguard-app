package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverLastRefresh
import kotlin.time.Instant

class PutGpgKeyserverLastRefreshImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgKeyserverLastRefresh {
    override fun invoke(instant: Instant?): IO<Unit> = settingsReadWriteRepository
        .setGpgKeyserverLastRefresh(instant)
}
