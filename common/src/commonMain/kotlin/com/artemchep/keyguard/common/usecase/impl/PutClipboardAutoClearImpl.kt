package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutClipboardAutoClear
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

class PutClipboardAutoClearImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutClipboardAutoClear {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(duration: Duration?): IO<Unit> = settingsReadWriteRepository
        .setClipboardClearDelay(duration)
}
