package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutUseExternalBrowser
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutUserExternalBrowserImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutUseExternalBrowser {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(useExternalBrowser: Boolean): IO<Unit> = settingsReadWriteRepository
        .setUseExternalBrowser(useExternalBrowser)
}
