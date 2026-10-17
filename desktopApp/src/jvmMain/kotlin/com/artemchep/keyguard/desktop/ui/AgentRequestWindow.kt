package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.artemchep.keyguard.di.VaultSessionContent
import com.artemchep.keyguard.feature.agent.AgentRequestUiState
import com.artemchep.keyguard.feature.keyguard.AuthScreen
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ic_keyguard
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.KeyguardTheme
import com.artemchep.keyguard.ui.theme.LocalExpressive
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.scope.UnboundKoinScope
import org.koin.core.scope.Scope

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun <T : Any> ApplicationScope.AgentRequestWindow(
    processLifecycleProvider: LePlatformLifecycleProvider,
    requestUiState: Loadable<AgentRequestUiState<T>>,
    title: String,
    authReason: TextHolder,
    focusTag: String,
    requestContent: @Composable (request: T, onDecision: (approved: Boolean, reason: String) -> Unit) -> Unit,
) {
    val updatedRequest by rememberUpdatedState(requestUiState)
    val onDeny: () -> Unit = {
        // Closing the window = deny.
        (updatedRequest as? Loadable.Ok)
            ?.value
            ?.onDecision
            ?.invoke(false, "window_closed")
    }
    val focusRequestKey = (requestUiState as? Loadable.Ok)
        ?.value
        ?.id
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
        focusRequestKey = focusRequestKey,
        focusTag = focusTag,
        icon = painterResource(Res.drawable.ic_keyguard),
    ) {
        KeyguardWindowEssentials(
            processLifecycleProvider = processLifecycleProvider,
            onMinimizeRequest = {},
        ) {
            KeyguardTheme {
                val scr = AuthScreen(
                    reason = authReason,
                    style = AuthScreen.Style.DIALOG,
                    onCancel = onDeny,
                    expiresAt = updatedRequest.getOrNull()?.expiresAt,
                )
                CompositionLocalProvider(
                    LocalAuthScreen provides scr,
                    LocalExpressive provides GlobalExpressive.current,
                ) {
                    KeyguardPopupScaffold {
                        AgentUnlockWindow(
                            requestUiState = requestUiState,
                            requestContent = requestContent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T : Any> AgentUnlockWindow(
    requestUiState: Loadable<AgentRequestUiState<T>>,
    requestContent: @Composable (request: T, onDecision: (approved: Boolean, reason: String) -> Unit) -> Unit,
) {
    ManualAppScreen { vaultState ->
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
                        // Provide the session Scope so that the content can
                        // read the vault, e.g. to resolve the key's title.
                        VaultSessionContent(vaultState.session) {
                            requestContent(v.request, v.onDecision)
                        }
                    }
                }
            }
        }
    }
}
