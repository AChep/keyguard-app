package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutSshAgentBookmarksConfig

class PutSshAgentBookmarksConfigImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutSshAgentBookmarksConfig {
    override fun invoke(enabled: Boolean): IO<Unit> = settingsReadWriteRepository
        .setSshAgentBookmarksConfig(enabled)
}
