package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.artemchep.keyguard.KeyguardWindowEssentials
import com.artemchep.keyguard.KeyguardWindowScaffold
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.passkey.WindowsPasskeyService
import com.artemchep.keyguard.common.service.passkey.WindowsPasskeyUiRequest
import com.artemchep.keyguard.feature.keyguard.AuthScreen
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnMain
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.BackHandler
import com.artemchep.keyguard.feature.navigation.LocalNavigationRouterNode
import com.artemchep.keyguard.feature.navigation.LocalNavigationStore
import com.artemchep.keyguard.feature.navigation.NavigationRouterNode
import com.artemchep.keyguard.feature.navigation.NavigationStore
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ic_keyguard
import com.artemchep.keyguard.res.passkeys
import com.artemchep.keyguard.ui.theme.KeyguardTheme
import kotlinx.coroutines.GlobalScope
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Reads the request state here, so its transitions do not recompose the whole application. */
@Composable
internal fun ApplicationScope.WindowsPasskeyRequestHost(
    service: WindowsPasskeyService,
    processLifecycleProvider: LePlatformLifecycleProvider,
) {
    val state = service.ui.collectAsState().value ?: return
    WindowsPasskeyRequestWindow(
        state = state,
        processLifecycleProvider = processLifecycleProvider,
        onCancel = { service.cancel(state.request.id).launchIn(GlobalScope) },
    )
}

/** Existing vault/unlock screens run in a request-owned window, independently of the main window. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun ApplicationScope.WindowsPasskeyRequestWindow(
    state: WindowsPasskeyUiRequest,
    processLifecycleProvider: LePlatformLifecycleProvider,
    onCancel: () -> Unit,
) {
    val navigationBackHandler = remember(state.request.id) { BackHandler() }
    val navigationStore = remember(state.request.id) { NavigationStore() }
    val navigationRouter = remember(state.request.id) { NavigationRouterNode(id = "root", parent = null) }
    PopupComposeWindow(
        title = stringResource(Res.string.passkeys),
        onCloseRequest = onCancel,
        state = rememberWindowState(
            size = DpSize(480.dp, 640.dp),
            position = WindowPosition(Alignment.Center),
        ),
        icon = painterResource(Res.drawable.ic_keyguard),
        focusRequestKey = state.request.id,
        focusTag = "WindowsPasskeys",
        onKeyEvent = navigationBackHandler::handleKeyEvent,
    ) {
        KeyguardWindowEssentials(processLifecycleProvider = processLifecycleProvider, onMinimizeRequest = {}) {
            KeyguardTheme {
                CompositionLocalProvider(
                    LocalNavigationStore provides navigationStore,
                    LocalNavigationRouterNode provides navigationRouter,
                    LocalAuthScreen provides AuthScreen(
                        reason = TextHolder.Value(state.request.rpId),
                        style = AuthScreen.Style.DIALOG,
                        onCancel = onCancel,
                        expiresAt = state.expiresAt,
                    ),
                ) {
                    KeyguardWindowScaffold(
                        navigationBackHandler = navigationBackHandler,
                        onRootPop = onCancel,
                    ) {
                        WindowsPasskeyAppScreen(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowsPasskeyAppScreen(state: WindowsPasskeyUiRequest) {
    ManualAppScreen { vault ->
        when (vault) {
            is VaultState.Create -> ManualAppScreenOnCreate(vault)
            is VaultState.Unlock -> ManualAppScreenOnUnlock(vault)
            is VaultState.Loading -> ManualAppScreenOnLoading(vault)
            is VaultState.Main -> {
                val mode = state.mode
                if (mode == null) {
                    ManualAppScreenOnLoading()
                } else {
                    key(state.request.id) {
                        CompositionLocalProvider(LocalAppMode provides mode) {
                            ManualAppScreenOnMain(vault)
                        }
                    }
                }
            }
        }
    }
}
