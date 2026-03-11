package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.KeyguardPopupScaffold
import com.artemchep.keyguard.KeyguardWindowEssentials
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.service.quicksearch.QuickSearchWindowState
import com.artemchep.keyguard.feature.home.vault.quicksearch.LocalQuickSearchActivationRevision
import com.artemchep.keyguard.feature.keyguard.AuthScreen
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.home.vault.quicksearch.LocalQuickSearchDismiss
import com.artemchep.keyguard.feature.home.vault.quicksearch.QuickSearchAppRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.LocalNavigationRouterNode
import com.artemchep.keyguard.feature.navigation.LocalNavigationStore
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouterNode
import com.artemchep.keyguard.feature.navigation.NavigationStore
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.platform.recordLogDebug
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ic_keyguard
import com.artemchep.keyguard.ui.theme.KeyguardTheme
import org.jetbrains.compose.resources.painterResource
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlin.Int

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ApplicationScope.QuickSearchWindow(
    processLifecycleProvider: LePlatformLifecycleProvider,
    windowState: QuickSearchWindowState,
    onDismissRequest: () -> Unit,
) {
    val updatedOnDismissRequest by rememberUpdatedState(onDismissRequest)
    var activationRevision by remember(windowState.requestRevision) {
        mutableStateOf(windowState.requestRevision)
    }

    Window(
        onCloseRequest = updatedOnDismissRequest,
        title = "Quick Search",
        state = rememberWindowState(
            size = DpSize(720.dp, 560.dp),
            position = WindowPosition(Alignment.Center),
        ),
        decoration = WindowDecoration.Undecorated(),
        visible = windowState.visible,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        icon = painterResource(Res.drawable.ic_keyguard),
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                updatedOnDismissRequest()
                true
            } else {
                false
            }
        },
    ) {
        var hasBeenFocused by remember(windowState.requestRevision) {
            mutableStateOf(false)
        }
        DisposableEffect(window, windowState.requestRevision) {
            val activationListener = object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent?) {
                    recordLogDebug {
                        "QuickSearchWindow[${windowState.requestRevision}] window activated ($hasBeenFocused) $e"
                    }
                    hasBeenFocused = true
                    activationRevision += 1
                }
            }
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    recordLogDebug {
                        "QuickSearchWindow[${windowState.requestRevision}] focus gained ($hasBeenFocused) $e"
                    }
                    hasBeenFocused = true
                }

                override fun windowLostFocus(e: WindowEvent?) {
                    recordLogDebug {
                        "QuickSearchWindow[${windowState.requestRevision}] focus lost ($hasBeenFocused) $e"
                    }
                    if (hasBeenFocused) {
                        updatedOnDismissRequest()
                    }
                }
            }
            window.addWindowListener(activationListener)
            window.addWindowFocusListener(listener)
            onDispose {
                window.removeWindowListener(activationListener)
                window.removeWindowFocusListener(listener)
            }
        }

        LaunchedEffect(windowState.visible, windowState.requestRevision) {
            if (!windowState.visible) {
                return@LaunchedEffect
            }

            requestAppForeground(
                tag = "QuickSearchWindow",
                requestRevision = windowState.requestRevision,
            )
            val visible = window.awaitVisible(
                tag = "QuickSearchWindow",
                requestRevision = windowState.requestRevision,
            )
            if (!visible) {
                return@LaunchedEffect
            }

            val focusAcquired = window.requestFocusWithRetry(
                tag = "QuickSearchWindow",
                requestRevision = windowState.requestRevision,
            )
            if (focusAcquired) {
                activationRevision += 1
            }
        }

        KeyguardWindowEssentials(
            processLifecycleProvider = processLifecycleProvider,
        ) {
            KeyguardTheme {
                KeyguardPopupScaffold {
                    QuickSearchWindowContent(
                        activationRevision = activationRevision,
                        onDismissRequest = updatedOnDismissRequest,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickSearchWindowContent(
    activationRevision: Int,
    onDismissRequest: () -> Unit,
) {
    // Create separate navigation store and router
    // so it can independently recover the navigation
    // state.
    val navigationStore = remember {
        NavigationStore()
    }
    val navigationRouter = remember {
        NavigationRouterNode(
            id = "root",
            parent = null,
        )
    }

    CompositionLocalProvider(
        LocalAppMode provides AppMode.QuickSearch,
        LocalQuickSearchDismiss provides onDismissRequest,
        LocalQuickSearchActivationRevision provides activationRevision,
        // navigation
        LocalNavigationStore provides navigationStore,
        LocalNavigationRouterNode provides navigationRouter,
    ) {
        NavigationNode(
            id = "App:QuickSearch",
            route = QuickSearchAppRoute,
        )
    }
}
