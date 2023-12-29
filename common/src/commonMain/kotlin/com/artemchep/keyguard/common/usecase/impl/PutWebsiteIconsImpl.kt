package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutWebsiteIcons
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutWebsiteIconsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutWebsiteIcons {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(websiteIcons: Boolean): IO<Unit> = settingsReadWriteRepository
        .setWebsiteIcons(websiteIcons)
}
