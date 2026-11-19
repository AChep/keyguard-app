package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBrowserExtensionRemovedConnectors

class PutBrowserExtensionRemovedConnectorsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBrowserExtensionRemovedConnectors {
    override fun invoke(browsers: Set<BrowserConnectorBrowser>): IO<Unit> = settingsReadWriteRepository
        .setBrowserExtensionRemovedConnectors(browsers.mapTo(mutableSetOf()) { it.id })
}
