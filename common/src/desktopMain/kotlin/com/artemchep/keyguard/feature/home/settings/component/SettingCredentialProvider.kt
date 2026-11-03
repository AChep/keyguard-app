package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.passkey.WindowsPasskeyService
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

private const val WINDOWS_PASSKEY_SETTINGS_URI = "ms-settings:passkeys-advancedoptions"

actual fun settingCredentialProviderProvider(
    koinScope: Scope,
): SettingComponent = if (CurrentPlatform is Platform.Desktop.Windows) {
    settingCredentialProviderProvider(
        windowsPasskeyService = koinScope.get(),
        confirmationRouteFactory = koinScope.get(),
        showMessage = koinScope.get(),
        windowCoroutineScope = koinScope.get(),
    )
} else {
    // Only Windows lets Keyguard register as
    // a system passkey provider.
    flowOf(null)
}

fun settingCredentialProviderProvider(
    windowsPasskeyService: WindowsPasskeyService,
    confirmationRouteFactory: ConfirmationRouteFactory,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    windowsPasskeyService.status,
    windowsPasskeyService.cacheFailed,
) { status, cacheFailed ->
    val onRegisteredChange = { shouldRegister: Boolean ->
        windowsPasskeyService.setRegistered(shouldRegister)
            .launchIn(windowCoroutineScope)
        Unit
    }
    val onOpenSettings = {
        runCatchingNonFatal {
            openWindowsPasskeySettings()
        }.onFailure {
            windowCoroutineScope.launch {
                val message = ToastMessage(
                    type = ToastMessage.Type.ERROR,
                    title = getString(Res.string.error_failed_open_uri),
                )
                showMessage.copy(message)
            }
        }
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "windows",
                "passkey",
                "provider",
            ),
        ),
    ) {
        SettingCredentialProvider(
            status = status,
            cacheFailed = cacheFailed,
            confirmationRouteFactory = confirmationRouteFactory,
            onRegisteredChange = onRegisteredChange,
            onOpenSettings = onOpenSettings,
        )
    }
}

private fun openWindowsPasskeySettings() {
    // AWT's browse action sends custom URI schemes to the default browser.
    ProcessBuilder("explorer.exe", WINDOWS_PASSKEY_SETTINGS_URI).start()
}

@Composable
private fun SettingCredentialProvider(
    status: WindowsPasskeyStatus,
    cacheFailed: Boolean,
    confirmationRouteFactory: ConfirmationRouteFactory,
    onRegisteredChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val updatedOnRegisteredChange by rememberUpdatedState(onRegisteredChange)

    val registered = status.isRegistered
    val text = if (registered && cacheFailed) {
        Res.string.passkey_provider_cache_error
    } else {
        status.text()
    }
    // Unsupported, unpackaged or unavailable providers
    // can not be registered.
    val canChangeRegistration = registered ||
            status == WindowsPasskeyStatus.UNREGISTERED

    val title = stringResource(Res.string.passkeys)
    val consent = stringResource(Res.string.passkey_provider_consent)
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.Key,
        title = title,
        text = stringResource(text),
        footer = if (registered) {
            // composable
            {
                SettingCredentialProviderOpenSettings(
                    onClick = onOpenSettings,
                )
            }
        } else {
            null
        },
        checked = registered,
        onCheckedChange = if (canChangeRegistration) {
            { shouldRegister ->
                if (shouldRegister) {
                    // Registering shares the passkey metadata
                    // with the system, so ask for consent first.
                    val route = confirmationRouteFactory.registerRouteResultReceiver(
                        args = ConfirmationRoute.Args(
                            icon = icon(Icons.Outlined.Key),
                            title = title,
                            message = consent,
                        ),
                    ) { result ->
                        if (result is ConfirmationResult.Confirm) {
                            updatedOnRegisteredChange(true)
                        }
                    }
                    val intent = NavigationIntent.NavigateToRoute(
                        route = route,
                    )
                    navigationController.queue(intent)
                } else {
                    onRegisteredChange(false)
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun SettingCredentialProviderOpenSettings(
    onClick: () -> Unit,
) {
    TextButton(
        modifier = Modifier
            .padding(
                horizontal = getSettingsButtonStartPadding(),
                vertical = 4.dp,
            ),
        onClick = onClick,
    ) {
        Text(
            text = stringResource(Res.string.passkey_provider_open_settings),
        )
    }
}

private fun WindowsPasskeyStatus.text(): StringResource = when (this) {
    WindowsPasskeyStatus.UNSUPPORTED -> Res.string.passkey_provider_unsupported
    WindowsPasskeyStatus.UNPACKAGED -> Res.string.passkey_provider_unpackaged
    WindowsPasskeyStatus.UNREGISTERED -> Res.string.passkey_provider_unregistered
    WindowsPasskeyStatus.DISABLED -> Res.string.passkey_provider_disabled
    WindowsPasskeyStatus.ENABLED -> Res.string.passkey_provider_enabled
    WindowsPasskeyStatus.UNAVAILABLE -> Res.string.passkey_provider_unavailable
}
