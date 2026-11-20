package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AppUpdateLevel
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAppUpdateLevel

class PutAppUpdateLevelImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAppUpdateLevel {
    override fun invoke(level: AppUpdateLevel): IO<Unit> = settingsReadWriteRepository
        .setAppUpdateLevel(level)
}
