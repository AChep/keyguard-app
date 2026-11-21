package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.browser.LocalBrowserExtensionSettingsState
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_connected_count_plural
import com.artemchep.keyguard.res.browser_extension_enable
import com.artemchep.keyguard.res.browser_extension_enable_text
import com.artemchep.keyguard.res.browser_extension_pairings_count_plural
import com.artemchep.keyguard.res.browser_extension_status_connector_failed
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.warning
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingBrowserExtensionProvider(
    koinScope: Scope,
) = browserExtensionSettingProvider {
    settingBrowserExtensionProvider(
        browserExtensionService = koinScope.get(),
        windowCoroutineScope = koinScope.get(),
    )
}

fun settingBrowserExtensionProvider(
    browserExtensionService: BrowserExtensionService,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = browserExtensionService.state
    .map { state ->
        // Builds without the CLI agent, which relays the extensions, do not offer it.
        state.enabled.takeIf { state.supported }
    }
    .distinctUntilChanged()
    .map { enabled ->
        if (enabled == null) {
            return@map null
        }

        val onCheckedChange = { shouldEnable: Boolean ->
            windowCoroutineScope.launch {
                browserExtensionService.setEnabled(shouldEnable)
            }
            Unit
        }

        SettingIi(
            platformClasses = browserExtensionPlatformClasses,
            search = SettingIi.Search(
                group = "autofill",
                tokens = browserExtensionSearchTokens,
            ),
        ) {
            // Only the browser extension screen knows the status.
            val screenState = LocalBrowserExtensionSettingsState.current
            LocalSettingPaneComponents.current.KgSwitch(
                icon = Icons.Outlined.Extension,
                title = {
                    Text(stringResource(Res.string.browser_extension_enable))
                },
                text = {
                    SettingBrowserExtensionStatus(screenState?.status)
                },
                checked = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }

@Composable
private fun SettingBrowserExtensionStatus(
    status: BrowserExtensionSettingsState.Status?,
) = when (status) {
    is BrowserExtensionSettingsState.Status.Paired -> {
        val paired = pluralStringResource(
            Res.plurals.browser_extension_pairings_count_plural,
            status.paired,
            status.paired,
        )
        val connected = status.connected
            .takeIf { it > 0 }
            ?.let { count ->
                pluralStringResource(
                    Res.plurals.browser_extension_connected_count_plural,
                    count,
                    count,
                )
            }
        val connectedColor = MaterialTheme.colorScheme.ok
        val text = buildAnnotatedString {
            append(paired)
            if (connected != null) {
                append(" · ")
                withStyle(SpanStyle(color = connectedColor)) {
                    append(connected)
                }
            }
        }
        Text(text)
    }

    BrowserExtensionSettingsState.Status.ConnectorFailed -> Text(
        text = stringResource(Res.string.browser_extension_status_connector_failed),
        color = MaterialTheme.colorScheme.warning,
    )

    null -> Text(stringResource(Res.string.browser_extension_enable_text))
}

/**
 * The desktop app serves the browser extension; the native macOS
 * app does not.
 */
internal val browserExtensionPlatformClasses = listOf(
    Platform.Desktop.Linux::class,
    Platform.Desktop.MacOS.Jvm::class,
    Platform.Desktop.Windows::class,
)

internal val browserExtensionSearchTokens = listOf(
    "browser",
    "extension",
    "autofill",
    "chrome",
    "firefox",
    "edge",
)

internal inline fun browserExtensionSettingProvider(
    provider: () -> SettingComponent,
): SettingComponent = platformSettingProvider(browserExtensionPlatformClasses, provider)
