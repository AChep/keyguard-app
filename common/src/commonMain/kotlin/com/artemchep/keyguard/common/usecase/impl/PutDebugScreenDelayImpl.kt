package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutDebugScreenDelay

class PutDebugScreenDelayImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutDebugScreenDelay {
    override fun invoke(screenDelay: Boolean): IO<Unit> = settingsReadWriteRepository
        .setDebugScreenDelay(screenDelay)
}
