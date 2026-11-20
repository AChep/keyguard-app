package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAppUpdateDelay
import kotlin.time.Duration

class PutAppUpdateDelayImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAppUpdateDelay {
    override fun invoke(duration: Duration): IO<Unit> = settingsReadWriteRepository
        .setAppUpdateDelay(duration)
}
