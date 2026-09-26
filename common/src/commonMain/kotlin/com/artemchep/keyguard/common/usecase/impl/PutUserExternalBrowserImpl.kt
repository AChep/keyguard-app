package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutUseExternalBrowser

class PutUserExternalBrowserImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutUseExternalBrowser {
    override fun invoke(useExternalBrowser: Boolean): IO<Unit> = settingsReadWriteRepository
        .setUseExternalBrowser(useExternalBrowser)
}
