package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBrowserExtensionEnabled

class PutBrowserExtensionEnabledImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBrowserExtensionEnabled {
    override fun invoke(enabled: Boolean): IO<Unit> = settingsReadWriteRepository
        .setBrowserExtensionEnabled(enabled)
}
