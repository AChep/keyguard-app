package com.artemchep.keyguard.feature.browser

import com.artemchep.keyguard.common.service.browser.BrowserConnectorClient
import com.artemchep.keyguard.platform.util.isRelease
import org.koin.dsl.module

/** The installer of the browser extension's connectors; only the desktop app has one. */
class BrowserConnectorModule {
    val module = module {
        single<BrowserConnectorClient> {
            // Debug builds also let the unpacked development build of the extension connect.
            BundledBrowserConnectorClient(dev = !isRelease)
        }
    }
}
