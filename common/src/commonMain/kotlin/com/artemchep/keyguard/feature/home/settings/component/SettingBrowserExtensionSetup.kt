package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.URL_BROWSER_EXTENSION_CHROME
import com.artemchep.keyguard.URL_BROWSER_EXTENSION_EDGE
import com.artemchep.keyguard.URL_BROWSER_EXTENSION_FIREFOX
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState.Setup
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState.SetupConnector
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionStore
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.browser.LocalBrowserExtensionSettingsState
import com.artemchep.keyguard.feature.home.vault.component.FlatSurfaceExpressive
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_setup_connector_installed
import com.artemchep.keyguard.res.browser_extension_setup_connector_installing
import com.artemchep.keyguard.res.browser_extension_setup_connector_no_browser
import com.artemchep.keyguard.res.browser_extension_setup_connector_title
import com.artemchep.keyguard.res.browser_extension_setup_connector_turn_on
import com.artemchep.keyguard.res.browser_extension_setup_extension_connected
import com.artemchep.keyguard.res.browser_extension_setup_extension_title
import com.artemchep.keyguard.res.browser_extension_setup_pair_connected_text
import com.artemchep.keyguard.res.browser_extension_setup_pair_text
import com.artemchep.keyguard.res.browser_extension_setup_pair_title
import com.artemchep.keyguard.res.browser_store_chrome
import com.artemchep.keyguard.res.browser_store_edge
import com.artemchep.keyguard.res.browser_store_firefox
import com.artemchep.keyguard.res.retry
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.okContainer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingBrowserExtensionSetupProvider(
    koinScope: Scope,
) = browserExtensionSettingProvider {
    settingBrowserExtensionSetupProvider(
        browserExtensionService = koinScope.get(),
    )
}

/**
 * The steps to set up the browser extension, on its settings screen
 * until a browser is paired. Not searchable: the screen reads them.
 */
fun settingBrowserExtensionSetupProvider(
    browserExtensionService: BrowserExtensionService,
): SettingComponent = browserExtensionService.state
    .map { it.showsSetup }
    .distinctUntilChanged()
    .map { showsSetup ->
        // Gone from the list once paired, so that the rows
        // around it get their shapes.
        if (!showsSetup) {
            return@map null
        }
        SettingIi(
            platformClasses = browserExtensionPlatformClasses,
        ) {
            SettingBrowserExtensionSetup()
        }
    }

@Composable
private fun SettingBrowserExtensionSetup() {
    val setup = LocalBrowserExtensionSettingsState.current?.setup
        ?: return
    FlatSurfaceExpressive(
        shapeState = LocalSettingItemShape.current,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            // Builds without connectors skip the first step.
            val connector = setup.connector
            val offset = if (connector != null) 1 else 0
            if (connector != null) {
                SettingBrowserExtensionSetupConnector(
                    number = 1,
                    connector = connector,
                )
            }
            SettingBrowserExtensionSetupExtension(
                number = offset + 1,
                setup = setup,
            )
            SettingBrowserExtensionSetupPair(
                number = offset + 2,
                setup = setup,
            )
        }
    }
}

@Composable
private fun SettingBrowserExtensionSetupConnector(
    number: Int,
    connector: SetupConnector,
) {
    val onRetry = when (connector) {
        is SetupConnector.NoBrowser -> connector.onRetry
        is SetupConnector.Failed -> connector.onRetry
        else -> null
    }
    SettingBrowserExtensionSetupStep(
        number = number,
        done = connector is SetupConnector.Installed,
        title = stringResource(Res.string.browser_extension_setup_connector_title),
        text = {
            SettingBrowserExtensionSetupConnectorText(connector)
        },
        trailing = if (onRetry != null) {
            // composable
            {
                TextButton(
                    modifier = Modifier
                        .align(Alignment.CenterVertically),
                    onClick = onRetry,
                ) {
                    Text(stringResource(Res.string.retry))
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun SettingBrowserExtensionSetupConnectorText(
    connector: SetupConnector,
) = when (connector) {
    is SetupConnector.Installed -> {
        val browsers = connector.browsers
            .map { browser -> browser.title() }
            .joinToString(separator = ", ")
        Text(stringResource(Res.string.browser_extension_setup_connector_installed, browsers))
    }

    SetupConnector.Busy -> Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyguardLoadingIndicator(
            modifier = Modifier
                .size(16.dp),
        )
        Spacer(
            modifier = Modifier
                .width(8.dp),
        )
        Text(stringResource(Res.string.browser_extension_setup_connector_installing))
    }

    SetupConnector.TurnOnBrowser ->
        Text(stringResource(Res.string.browser_extension_setup_connector_turn_on))

    is SetupConnector.NoBrowser ->
        Text(stringResource(Res.string.browser_extension_setup_connector_no_browser))

    is SetupConnector.Failed -> Text(
        text = browserConnectorErrorText(connector.error),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SettingBrowserExtensionSetupExtension(
    number: Int,
    setup: Setup,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    SettingBrowserExtensionSetupStep(
        number = number,
        done = setup.extensionConnected,
        title = stringResource(Res.string.browser_extension_setup_extension_title),
        text = if (setup.extensionConnected) {
            // composable
            {
                Text(stringResource(Res.string.browser_extension_setup_extension_connected))
            }
        } else {
            null
        },
        // Do not offer the links if there's nothing to open them in.
        content = if (CurrentPlatform.hasBrowser()) {
            // composable
            {
                FlowRow(
                    modifier = Modifier
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    setup.stores.forEach { store ->
                        FilledTonalButton(
                            onClick = {
                                val intent = NavigationIntent.NavigateToBrowser(
                                    url = store.url,
                                )
                                navigationController.queue(intent)
                            },
                        ) {
                            Text(stringResource(store.titleRes()))
                        }
                    }
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun SettingBrowserExtensionSetupPair(
    number: Int,
    setup: Setup,
) {
    val text = if (setup.extensionConnected) {
        Res.string.browser_extension_setup_pair_connected_text
    } else {
        Res.string.browser_extension_setup_pair_text
    }
    SettingBrowserExtensionSetupStep(
        number = number,
        // The guide hides once a browser is paired.
        done = false,
        title = stringResource(Res.string.browser_extension_setup_pair_title),
        text = {
            Text(stringResource(text))
        },
    )
}

/** A numbered step, aligned with the rows of the settings. */
@Composable
private fun SettingBrowserExtensionSetupStep(
    number: Int,
    done: Boolean,
    title: String,
    text: (@Composable () -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.contentPadding,
                vertical = 8.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        SettingBrowserExtensionSetupBadge(
            number = number,
            done = done,
        )
        Spacer(
            modifier = Modifier
                .width(16.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f),
        ) {
            FlatItemTextContent(
                title = {
                    Text(title)
                },
                text = text,
            )
            content?.invoke(this)
        }
        if (trailing != null) {
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            trailing()
        }
    }
}

/** The number of a step, or a check once it's done. */
@Composable
private fun SettingBrowserExtensionSetupBadge(
    number: Int,
    done: Boolean,
) {
    val backgroundColor = if (done) {
        MaterialTheme.colorScheme.okContainer
            .combineAlpha(DisabledEmphasisAlpha)
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    // The size of a leading icon of a row.
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                modifier = Modifier
                    .size(16.dp),
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.ok,
            )
        } else {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private val BrowserExtensionStore.url: String
    get() = when (this) {
        BrowserExtensionStore.CHROME_WEB_STORE -> URL_BROWSER_EXTENSION_CHROME
        BrowserExtensionStore.EDGE_ADD_ONS -> URL_BROWSER_EXTENSION_EDGE
        BrowserExtensionStore.FIREFOX_ADD_ONS -> URL_BROWSER_EXTENSION_FIREFOX
    }

private fun BrowserExtensionStore.titleRes(): StringResource = when (this) {
    BrowserExtensionStore.CHROME_WEB_STORE -> Res.string.browser_store_chrome
    BrowserExtensionStore.EDGE_ADD_ONS -> Res.string.browser_store_edge
    BrowserExtensionStore.FIREFOX_ADD_ONS -> Res.string.browser_store_firefox
}
