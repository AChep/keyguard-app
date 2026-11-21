package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.URL_BROWSER_EXTENSION_CHROME
import com.artemchep.keyguard.URL_BROWSER_EXTENSION_EDGE
import com.artemchep.keyguard.URL_BROWSER_EXTENSION_FIREFOX
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionStore
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.browser.LocalBrowserExtensionSettingsState
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_store_chrome
import com.artemchep.keyguard.res.browser_store_edge
import com.artemchep.keyguard.res.browser_store_firefox
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardBrowserChrome
import com.artemchep.keyguard.ui.icons.KeyguardBrowserEdge
import com.artemchep.keyguard.ui.icons.KeyguardBrowserFirefox
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingBrowserExtensionStoresProvider(
    koinScope: Scope,
) = browserExtensionSettingProvider {
    settingBrowserExtensionStoresProvider(
        browserExtensionService = koinScope.get(),
    )
}

/**
 * The extension store links shown below the enable switch on the browser
 * extension settings screen. Not searchable: the screen reads the stores.
 */
fun settingBrowserExtensionStoresProvider(
    browserExtensionService: BrowserExtensionService,
): SettingComponent {
    // Do not offer the links if there's nothing to open them in.
    if (!CurrentPlatform.hasBrowser()) {
        return flowOf(null)
    }

    return browserExtensionService.state
        .map { it.supported }
        .distinctUntilChanged()
        .map { supported ->
            if (!supported) {
                return@map null
            }
            SettingIi(
                platformClasses = browserExtensionPlatformClasses,
            ) {
                SettingBrowserExtensionStores()
            }
        }
}

@Composable
private fun SettingBrowserExtensionStores() {
    val stores = LocalBrowserExtensionSettingsState.current?.stores
        ?: BrowserExtensionStore.entries
    val parentShape = LocalSettingItemShape.current
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    Column {
        stores.forEachIndexed { index, store ->
            key(store) {
                val top = if (index == 0) parentShape and ShapeState.START else 0
                val bottom = if (index == stores.lastIndex) parentShape and ShapeState.END else 0
                CompositionLocalProvider(
                    LocalSettingItemShape provides (top or bottom),
                ) {
                    LocalSettingPaneComponents.current.KgAction(
                        icon = store.icon(),
                        subIcon = Icons.Outlined.KeyguardWebsite,
                        title = stringResource(store.titleRes()),
                        trailing = {
                            ChevronIcon()
                        },
                        onClick = {
                            val intent = NavigationIntent.NavigateToBrowser(
                                url = store.url,
                            )
                            navigationController.queue(intent)
                        },
                    )
                }
            }
        }
    }
}

private val BrowserExtensionStore.url: String
    get() = when (this) {
        BrowserExtensionStore.CHROME_WEB_STORE -> URL_BROWSER_EXTENSION_CHROME
        BrowserExtensionStore.EDGE_ADD_ONS -> URL_BROWSER_EXTENSION_EDGE
        BrowserExtensionStore.FIREFOX_ADD_ONS -> URL_BROWSER_EXTENSION_FIREFOX
    }

private fun BrowserExtensionStore.icon(): ImageVector = when (this) {
    BrowserExtensionStore.CHROME_WEB_STORE -> Icons.Outlined.KeyguardBrowserChrome
    BrowserExtensionStore.EDGE_ADD_ONS -> Icons.Outlined.KeyguardBrowserEdge
    BrowserExtensionStore.FIREFOX_ADD_ONS -> Icons.Outlined.KeyguardBrowserFirefox
}

private fun BrowserExtensionStore.titleRes(): StringResource = when (this) {
    BrowserExtensionStore.CHROME_WEB_STORE -> Res.string.browser_store_chrome
    BrowserExtensionStore.EDGE_ADD_ONS -> Res.string.browser_store_edge
    BrowserExtensionStore.FIREFOX_ADD_ONS -> Res.string.browser_store_firefox
}
