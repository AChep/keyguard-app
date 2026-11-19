package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetBrowserExtensionRemovedConnectors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class GetBrowserExtensionRemovedConnectorsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetBrowserExtensionRemovedConnectors {
    private val sharedFlow = settingsReadRepository.getBrowserExtensionRemovedConnectors()
        .map { ids ->
            // Drops the browsers that this version doesn't know.
            ids
                .mapNotNull { id -> BrowserConnectorBrowser.entries.firstOrNull { it.id == id } }
                .toSet()
        }
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
