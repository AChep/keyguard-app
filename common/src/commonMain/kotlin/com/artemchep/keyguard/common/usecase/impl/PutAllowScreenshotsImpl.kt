package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AllowScreenshots
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAllowScreenshots

class PutAllowScreenshotsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAllowScreenshots {
    override fun invoke(allowScreenshots: AllowScreenshots): IO<Unit> = settingsReadWriteRepository
        .setAllowScreenshots(allowScreenshots)
}
