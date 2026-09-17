package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutClipboardAutoClear
import kotlin.time.Duration

class PutClipboardAutoClearImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutClipboardAutoClear {
    override fun invoke(duration: Duration?): IO<Unit> = settingsReadWriteRepository
        .setClipboardClearDelay(duration)
}
