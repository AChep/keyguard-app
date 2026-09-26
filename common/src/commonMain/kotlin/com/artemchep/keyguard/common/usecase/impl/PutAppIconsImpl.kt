package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAppIcons

class PutAppIconsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAppIcons {
    override fun invoke(appIcons: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAppIcons(appIcons)
}
