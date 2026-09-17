package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverAutoRefresh

class PutGpgKeyserverAutoRefreshImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgKeyserverAutoRefresh {
    override fun invoke(autoRefresh: Boolean) = settingsReadWriteRepository
        .setGpgKeyserverAutoRefresh(autoRefresh)
}
