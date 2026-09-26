package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCloseToTray

class PutCloseToTrayImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCloseToTray {
    override fun invoke(closeToTray: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCloseToTray(closeToTray)
}
