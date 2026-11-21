package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.browser.BrowserConnectorError
import com.artemchep.keyguard.common.service.browser.BrowserConnectorPackaging
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState.ConnectorStatus
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.browser.LocalBrowserExtensionSettingsState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_connector_error_browser_absent
import com.artemchep.keyguard.res.browser_extension_connector_error_failed
import com.artemchep.keyguard.res.browser_extension_connector_error_foreign_file
import com.artemchep.keyguard.res.browser_extension_connector_error_host_alias
import com.artemchep.keyguard.res.browser_extension_connector_error_host_missing
import com.artemchep.keyguard.res.browser_extension_connector_error_unsafe_file
import com.artemchep.keyguard.res.browser_extension_connector_error_unsupported
import com.artemchep.keyguard.res.browser_extension_connector_error_update_required
import com.artemchep.keyguard.res.browser_extension_connector_installed
import com.artemchep.keyguard.res.browser_extension_connector_needs_repair
import com.artemchep.keyguard.res.browser_extension_connector_not_installed
import com.artemchep.keyguard.res.browser_extension_connectors_flatpak_note
import com.artemchep.keyguard.res.browser_extension_connectors_note
import com.artemchep.keyguard.res.browser_name_brave
import com.artemchep.keyguard.res.browser_name_chrome
import com.artemchep.keyguard.res.browser_name_chromium
import com.artemchep.keyguard.res.browser_name_edge
import com.artemchep.keyguard.res.browser_name_firefox
import com.artemchep.keyguard.res.browser_name_flatpak
import com.artemchep.keyguard.res.browser_name_vivaldi
import com.artemchep.keyguard.res.error_failed_unknown
import com.artemchep.keyguard.res.retry
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.warning
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

@Suppress("UnusedParameter") // The settings hub passes every provider its scope.
fun settingBrowserExtensionConnectorsProvider(
    koinScope: Scope,
) = browserExtensionSettingProvider {
    settingBrowserExtensionConnectorsProvider()
}

/**
 * A switch per browser that keeps its connector installed, on the browser
 * extension settings screen only. Not searchable: the screen reads the
 * connectors' report.
 */
fun settingBrowserExtensionConnectorsProvider(): SettingComponent = flowOf(
    SettingIi(
        platformClasses = browserExtensionPlatformClasses,
    ) {
        SettingBrowserExtensionConnectors()
    },
)

@Composable
private fun SettingBrowserExtensionConnectors() {
    val state = LocalBrowserExtensionSettingsState.current
        ?: return
    val connectors = state.connectors
        ?: return
    Column {
        SettingBrowserExtensionText(
            text = stringResource(Res.string.browser_extension_connectors_note),
        )
        if (connectors.packaging == BrowserConnectorPackaging.FLATPAK) {
            SettingBrowserExtensionText(
                text = stringResource(Res.string.browser_extension_connectors_flatpak_note),
            )
        }
        if (connectors.packaging == null && connectors.busy) {
            SkeletonItem()
        }
        SettingBrowserExtensionRows(
            items = connectors.items,
            itemKey = { connector -> connector.id },
        ) { connector ->
            SettingBrowserExtensionConnector(connector)
        }
        val error = connectors.error
        if (error != null) {
            // After a failed change the rows of the last report stay;
            // retrying repeats the operation that failed.
            val onRetry = connectors.onRetry
            FlatSimpleNote(
                type = SimpleNote.Type.ERROR,
                text = browserConnectorErrorText(error),
                trailing = if (onRetry != null) {
                    // composable
                    {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(Res.string.retry))
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun SettingBrowserExtensionConnector(
    connector: BrowserExtensionSettingsState.Connector,
) {
    val title = connector.browsers
        .map { browser -> browser.title() }
        .joinToString(separator = ", ")
    val statusColor = when (connector.status) {
        ConnectorStatus.INSTALLED -> MaterialTheme.colorScheme.ok
        ConnectorStatus.NEEDS_REPAIR -> MaterialTheme.colorScheme.warning
        ConnectorStatus.NOT_INSTALLED -> LocalContentColor.current.combineAlpha(MediumEmphasisAlpha)
    }
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.Web,
        title = {
            Text(title)
        },
        text = {
            Text(
                text = stringResource(connector.status.titleRes()),
                color = statusColor,
            )
        },
        checked = connector.checked,
        onCheckedChange = connector.onCheckedChange,
    )
}

/**
 * Rows that look like one group, whatever the shape of the setting.
 *
 * @param itemKey A stable ID of the item, so that a row keeps its state
 * when the list reorders.
 */
@Composable
internal fun <T> SettingBrowserExtensionRows(
    items: List<T>,
    itemKey: (T) -> Any,
    content: @Composable (T) -> Unit,
) {
    items.forEachIndexed { index, item ->
        key(itemKey(item)) {
            val shape = getShapeState(items, index) { _, _ -> true }
            CompositionLocalProvider(
                LocalSettingItemShape provides shape,
            ) {
                content(item)
            }
        }
    }
}

@Composable
internal fun SettingBrowserExtensionText(
    text: String,
) {
    Text(
        modifier = Modifier
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 8.dp,
            ),
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}

@Composable
internal fun browserConnectorErrorText(
    error: BrowserConnectorError,
): String = when (error.kind) {
    BrowserConnectorError.Kind.HOST_MISSING -> error.alias
        ?.let { alias -> stringResource(Res.string.browser_extension_connector_error_host_alias, alias) }
        ?: stringResource(Res.string.browser_extension_connector_error_host_missing)

    BrowserConnectorError.Kind.BROWSER_ABSENT ->
        stringResource(Res.string.browser_extension_connector_error_browser_absent)

    BrowserConnectorError.Kind.FOREIGN_FILE ->
        stringResource(Res.string.browser_extension_connector_error_foreign_file, error.path.orEmpty())

    BrowserConnectorError.Kind.UNSUPPORTED ->
        stringResource(Res.string.browser_extension_connector_error_unsupported)

    BrowserConnectorError.Kind.UNSAFE_FILE ->
        stringResource(Res.string.browser_extension_connector_error_unsafe_file, error.path.orEmpty())

    BrowserConnectorError.Kind.UPDATE_REQUIRED ->
        stringResource(Res.string.browser_extension_connector_error_update_required)

    BrowserConnectorError.Kind.FAILED -> error.detail
        ?.let { detail -> stringResource(Res.string.browser_extension_connector_error_failed, detail) }
        ?: stringResource(Res.string.error_failed_unknown)
}

/** The browser's name, such as "Google Chrome (Flatpak)" for its Flathub build. */
@Composable
internal fun BrowserConnectorBrowser.title(): String {
    val name = stringResource(nameRes())
    return if (flatpak) {
        stringResource(Res.string.browser_name_flatpak, name)
    } else {
        name
    }
}

private fun BrowserConnectorBrowser.nameRes(): StringResource = when (this) {
    BrowserConnectorBrowser.CHROME,
    BrowserConnectorBrowser.CHROME_FLATPAK,
    -> Res.string.browser_name_chrome

    BrowserConnectorBrowser.CHROMIUM,
    BrowserConnectorBrowser.CHROMIUM_FLATPAK,
    -> Res.string.browser_name_chromium

    BrowserConnectorBrowser.EDGE,
    BrowserConnectorBrowser.EDGE_FLATPAK,
    -> Res.string.browser_name_edge

    BrowserConnectorBrowser.BRAVE,
    BrowserConnectorBrowser.BRAVE_FLATPAK,
    -> Res.string.browser_name_brave

    BrowserConnectorBrowser.VIVALDI -> Res.string.browser_name_vivaldi

    BrowserConnectorBrowser.FIREFOX,
    BrowserConnectorBrowser.FIREFOX_FLATPAK,
    -> Res.string.browser_name_firefox
}

private fun ConnectorStatus.titleRes(): StringResource = when (this) {
    ConnectorStatus.INSTALLED -> Res.string.browser_extension_connector_installed
    ConnectorStatus.NOT_INSTALLED -> Res.string.browser_extension_connector_not_installed
    ConnectorStatus.NEEDS_REPAIR -> Res.string.browser_extension_connector_needs_repair
}
