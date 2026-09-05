package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.artemchep.keyguard.KeyguardPopupScaffold
import com.artemchep.keyguard.KeyguardWindowEssentials
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.agent.AgentRequest
import com.artemchep.keyguard.desktop.util.WindowFocusRequestEffect
import com.artemchep.keyguard.feature.agent.AgentRequestUiState
import com.artemchep.keyguard.feature.keyguard.AuthScreen
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ic_keyguard
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.KeyguardTheme
import com.artemchep.keyguard.ui.theme.LocalExpressive
import org.jetbrains.compose.resources.painterResource
import org.kodein.di.compose.withDI

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun <T : AgentRequest> ApplicationScope.AgentRequestWindow(
    processLifecycleProvider: LePlatformLifecycleProvider,
    requestUiState: Loadable<AgentRequestUiState<T>>,
    title: String,
    authReason: TextHolder,
    focusTag: String,
    vaultStateEffect: @Composable (vaultState: VaultState, request: T?, onDismiss: () -> Unit) -> Unit = { _, _, _ -> },
    requestContent: @Composable (request: T, onHandled: () -> Unit) -> Unit,
) {
    val updatedRequest by rememberUpdatedState(requestUiState)
    val onDeny: () -> Unit = {
        // Closing the window = deny.
        val r = updatedRequest as? Loadable.Ok
        r?.value?.request?.deferred?.complete(false)
        r?.value?.onRequestHandled?.invoke()
    }
    val focusRequest = (requestUiState as? Loadable.Ok)
        ?.value
        ?.request
    PopupComposeWindow(
        onCloseRequest = onDeny,
        title = title,
        state = rememberWindowState(
            size = DpSize(320.dp, 420.dp),
            position = WindowPosition(Alignment.Center),
        ),
        decoration = WindowDecoration.Undecorated(),
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        focusRequestKey = focusRequest,
        icon = painterResource(Res.drawable.ic_keyguard),
    ) {
        // Force the window to the foreground, even across
        // virtual desktops / workspaces. Only do so if the
        // actual underlying request changes.
        WindowFocusRequestEffect(
            window = window,
            visible = focusRequest != null,
            requestKey = focusRequest,
            tag = focusTag,
            requestId = focusRequest?.focusRequestLogId(),
            requestApplicationForeground = CurrentPlatform !is Platform.Desktop.MacOS,
        )

        KeyguardWindowEssentials(
            processLifecycleProvider = processLifecycleProvider,
            onMinimizeRequest = {},
        ) {
            KeyguardTheme {
                val scr = AuthScreen(
                    reason = authReason,
                    style = AuthScreen.Style.DIALOG,
                    onCancel = onDeny,
                    expiresAt = updatedRequest.getOrNull()?.request?.expiresAt,
                )
                CompositionLocalProvider(
                    LocalAuthScreen provides scr,
                    LocalExpressive provides GlobalExpressive.current,
                ) {
                    KeyguardPopupScaffold {
                        AgentUnlockWindow(
                            requestUiState = requestUiState,
                            vaultStateEffect = vaultStateEffect,
                            requestContent = requestContent,
                            onDismiss = onDeny,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T : AgentRequest> AgentUnlockWindow(
    requestUiState: Loadable<AgentRequestUiState<T>>,
    vaultStateEffect: @Composable (vaultState: VaultState, request: T?, onDismiss: () -> Unit) -> Unit,
    requestContent: @Composable (request: T, onHandled: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    ManualAppScreen { vaultState ->
        val request = (requestUiState as? Loadable.Ok)
            ?.value
            ?.request
        vaultStateEffect(vaultState, request, onDismiss)

        // Close the window once the request resolves, whether the user
        // approved/denied it or the agent manager expired it.
        LaunchedEffect(requestUiState) {
            val current = requestUiState.getOrNull()
                ?: return@LaunchedEffect
            current.request.deferred.join()
            onDismiss()
        }

        when (vaultState) {
            is VaultState.Create -> ManualAppScreenOnCreate(vaultState)
            is VaultState.Unlock -> ManualAppScreenOnUnlock(vaultState)
            is VaultState.Loading -> ManualAppScreenOnLoading(vaultState)
            is VaultState.Main -> {
                // If the UI state is loading then just show the
                // loader interface.
                when (requestUiState) {
                    is Loadable.Loading -> ManualAppScreenOnLoading()
                    is Loadable.Ok -> {
                        val v = requestUiState.value
                        // Provide the session DI so that the content can
                        // read the vault, e.g. to resolve the key's title.
                        withDI(vaultState.di) {
                            requestContent(v.request, v.onRequestHandled)
                        }
                    }
                }
            }
        }
    }
}

private fun AgentRequest.focusRequestLogId(): String {
    val requestId = notificationTag
        ?: System.identityHashCode(this).toString(16)
    return "$logType:$requestId"
}
