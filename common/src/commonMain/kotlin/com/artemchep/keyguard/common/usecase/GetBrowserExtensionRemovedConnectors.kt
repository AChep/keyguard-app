package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import kotlinx.coroutines.flow.Flow

/** The browsers whose connectors the user removed; Keyguard doesn't install them again. */
interface GetBrowserExtensionRemovedConnectors : () -> Flow<Set<BrowserConnectorBrowser>>
