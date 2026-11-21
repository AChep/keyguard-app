package com.artemchep.keyguard.feature.home.settings.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.service.browser.BrowserConnectorClient
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState
import com.artemchep.keyguard.feature.browserextension.ui.browserExtensionSettingsStateProducer
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_connectors_title
import com.artemchep.keyguard.res.browser_extension_pairings_title
import com.artemchep.keyguard.res.browser_extension_title
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.currentKoinScope

/**
 * The connectors and the paired browsers that the components of this
 * screen show; `null` outside of it.
 */
internal val LocalBrowserExtensionSettingsState = compositionLocalOf<BrowserExtensionSettingsState?> {
    null
}

@Composable
fun BrowserExtensionSettingsScreen() {
    val state = produceBrowserExtensionSettingsState()
    val items = rememberSettingsBrowserExtensionItems()
    CompositionLocalProvider(
        LocalBrowserExtensionSettingsState provides state,
    ) {
        SettingPaneContent(
            title = stringResource(Res.string.browser_extension_title),
            items = items,
        )
    }
}

@Composable
private fun produceBrowserExtensionSettingsState(
): BrowserExtensionSettingsState? = with(currentKoinScope()) {
    val service: BrowserExtensionService = get()
    // Only the desktop app installs connectors.
    val connectors: BrowserConnectorClient? = getOrNull()
    produceScreenState<BrowserExtensionSettingsState?>(
        key = "browser_extension_settings",
        initial = null,
        args = arrayOf(
            service,
            connectors,
        ),
    ) {
        browserExtensionSettingsStateProducer(
            service = service,
            connectors = connectors,
        )
    }
}

@Composable
fun rememberSettingsBrowserExtensionItems(
): ImmutableList<SettingPaneItem> {
    return remember {
        persistentListOf(
            SettingPaneItem.Group(
                key = "browser_extension.control_panel",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.BROWSER_EXTENSION),
                    SettingPaneItem.Item(Setting.BROWSER_EXTENSION_STORES),
                ),
            ),
            SettingPaneItem.Group(
                key = "browser_extension.pairings",
                title = TextHolder.Res(Res.string.browser_extension_pairings_title),
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.BROWSER_EXTENSION_PAIRINGS),
                ),
            ),
            SettingPaneItem.Group(
                key = "browser_extension.approvals",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.BROWSER_EXTENSION_APPROVAL_WINDOW),
                    SettingPaneItem.Item(Setting.BROWSER_EXTENSION_APPROVAL_TIMEOUT),
                ),
            ),
            SettingPaneItem.Group(
                key = "browser_extension.connectors",
                title = TextHolder.Res(Res.string.browser_extension_connectors_title),
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.BROWSER_EXTENSION_CONNECTORS),
                ),
            ),
            SettingPaneItem.Group(
                key = "browser_extension.history",
                list = persistentListOf(
                    SettingPaneItem.Item(Setting.BROWSER_EXTENSION_HISTORY),
                ),
            ),
        )
    }
}
