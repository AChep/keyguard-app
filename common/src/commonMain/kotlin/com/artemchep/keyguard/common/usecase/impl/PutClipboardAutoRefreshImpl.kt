package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutClipboardAutoRefresh
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

class PutClipboardAutoRefreshImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutClipboardAutoRefresh {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(duration: Duration?): IO<Unit> = settingsReadWriteRepository
        .setClipboardUpdateDuration(duration)
}
