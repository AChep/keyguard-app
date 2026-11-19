package com.artemchep.keyguard.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.KeyguardWindowEssentials
import com.artemchep.keyguard.KeyguardWindowScaffold
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.feature.keyguard.AuthScreen
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnMain
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.LocalNavigationStore
import com.artemchep.keyguard.feature.navigation.NavigationHost
import com.artemchep.keyguard.feature.navigation.NavigationStore
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ic_keyguard
import com.artemchep.keyguard.ui.theme.KeyguardTheme
import kotlin.time.Instant
import org.jetbrains.compose.resources.painterResource

/**
 * The vault in the picking [mode] of a passkey request, in a request-owned window
 * independent of the main window. The window unlocks the vault first. Once the vault
 * is unlocked, it shows the loading screen until the request sets a [mode].
 *
 * @param requestId Identifies the request: the window and its navigation belong to it.
 * @param reason What the unlock screen names, such as the relying party.
 * @param footer Shown below the vault.
 */
@Composable
@Suppress("LongParameterList")
internal fun ApplicationScope.PasskeyVaultWindow(
    requestId: Any,
    title: String,
    reason: String,
    expiresAt: Instant?,
    mode: AppMode?,
    focusTag: String,
    processLifecycleProvider: LePlatformLifecycleProvider,
    onCancel: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    VaultRequestWindow(
        requestId = requestId,
        title = title,
        reason = reason,
        expiresAt = expiresAt,
        focusTag = focusTag,
        processLifecycleProvider = processLifecycleProvider,
        onCancel = onCancel,
        footer = footer,
    ) { vault ->
        if (mode == null) {
            ManualAppScreenOnLoading()
        } else {
            key(requestId) {
                CompositionLocalProvider(LocalAppMode provides mode) {
                    ManualAppScreenOnMain(vault)
                }
            }
        }
    }
}

/**
 * A request-owned window independent of the main window. The window unlocks
 * the vault first, then shows the [main] content of the request.
 *
 * @param requestId Identifies the request: the window and its navigation belong to it.
 * @param reason What the unlock screen names, such as the relying party.
 * @param onShown Called once the window is on screen.
 * @param footer Shown below the vault.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("LongParameterList")
internal fun ApplicationScope.VaultRequestWindow(
    requestId: Any,
    title: String,
    reason: String,
    focusTag: String,
    processLifecycleProvider: LePlatformLifecycleProvider,
    onCancel: () -> Unit,
    expiresAt: Instant? = null,
    onShown: () -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
    main: @Composable (VaultState.Main) -> Unit,
) {
    val navigationHost = remember(requestId) { NavigationHost() }
    val navigationStore = remember(requestId) { NavigationStore() }
    PopupComposeWindow(
        title = title,
        onCloseRequest = onCancel,
        state = rememberWindowState(
            size = DpSize(480.dp, 640.dp),
            position = WindowPosition(Alignment.Center),
        ),
        icon = painterResource(Res.drawable.ic_keyguard),
        focusRequestKey = requestId,
        focusTag = focusTag,
        onKeyEvent = navigationHost::handleKeyEvent,
    ) {
        val currentOnShown by rememberUpdatedState(onShown)
        LaunchedEffect(requestId) {
            currentOnShown()
        }
        KeyguardWindowEssentials(processLifecycleProvider = processLifecycleProvider, onMinimizeRequest = {}) {
            KeyguardTheme {
                CompositionLocalProvider(
                    LocalNavigationStore provides navigationStore,
                    LocalAuthScreen provides AuthScreen(
                        reason = TextHolder.Value(reason),
                        style = AuthScreen.Style.DIALOG,
                        onCancel = onCancel,
                        expiresAt = expiresAt,
                    ),
                ) {
                    KeyguardWindowScaffold(
                        navigationHost = navigationHost,
                        onRootPop = onCancel,
                    ) {
                        if (footer == null) {
                            VaultRequestAppScreen(main)
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize(),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f),
                                ) {
                                    VaultRequestAppScreen(main)
                                }
                                footer()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultRequestAppScreen(
    main: @Composable (VaultState.Main) -> Unit,
) {
    ManualAppScreen { vault ->
        when (vault) {
            is VaultState.Create -> ManualAppScreenOnCreate(vault)
            is VaultState.Unlock -> ManualAppScreenOnUnlock(vault)
            is VaultState.Loading -> ManualAppScreenOnLoading(vault)
            is VaultState.Main -> main(vault)
        }
    }
}
