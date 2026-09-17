package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutWebsiteIcons

class PutWebsiteIconsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutWebsiteIcons {
    override fun invoke(websiteIcons: Boolean): IO<Unit> = settingsReadWriteRepository
        .setWebsiteIcons(websiteIcons)
}
