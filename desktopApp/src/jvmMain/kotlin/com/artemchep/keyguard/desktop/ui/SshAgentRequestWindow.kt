package com.artemchep.keyguard.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.artemchep.keyguard.KeyguardWindowEssentials
import com.artemchep.keyguard.KeyguardWindowScaffold
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.sshagent.SshAgentApprovalContent
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentGetListRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequest
import com.artemchep.keyguard.feature.sshagent.SshAgentRequestUiState
import com.artemchep.keyguard.feature.keyguard.AuthScreen
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.sshagent.SshAgentGetListContent
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ssh_agent
import com.artemchep.keyguard.res.ssh_client_request
import com.artemchep.keyguard.res.ic_keyguard
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.theme.GlobalExpressive
import com.artemchep.keyguard.ui.theme.KeyguardTheme
import com.artemchep.keyguard.ui.theme.LocalExpressive
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ApplicationScope.SshRequestWindow(
    processLifecycleProvider: LePlatformLifecycleProvider,
    sshAgentRequestUiState: Loadable<SshAgentRequestUiState>,
) {
    val updatedRequest by rememberUpdatedState(sshAgentRequestUiState)
    Window(
        onCloseRequest = {
            // Closing the window = deny.
            val r = updatedRequest as? Loadable.Ok
            r?.value?.request?.deferred?.complete(false)
            r?.value?.onRequestHandled?.invoke()
        },
        title = stringResource(Res.string.ssh_agent),
        state = rememberWindowState(
            size = DpSize(320.dp, 480.dp),
            position = WindowPosition(Alignment.Center),
        ),
        alwaysOnTop = true,
        resizable = false,
        icon = painterResource(Res.drawable.ic_keyguard),
    ) {
        // Force the window to the foreground, even across
        // virtual desktops / workspaces. Only do so if the
        // actual underlying request changes.
        if (sshAgentRequestUiState is Loadable.Ok) {
            LaunchedEffect(sshAgentRequestUiState.value) {
                window.toFront()
                window.requestFocus()
            }
        }

        KeyguardWindowEssentials(
            processLifecycleProvider = processLifecycleProvider,
        ) {
            KeyguardTheme {
                val scr = AuthScreen(
                    reason = TextHolder.Res(Res.string.ssh_client_request),
                )
                CompositionLocalProvider(
                    LocalAuthScreen provides scr,
                    LocalExpressive provides GlobalExpressive.current,
                ) {
                    KeyguardWindowScaffold {
                        SshAgentUnlockWindow(
                            sshAgentRequestUiState = sshAgentRequestUiState,
                            onDismiss = {
                                // Closing the window = deny.
                                val r = updatedRequest as? Loadable.Ok
                                //   r?.value?.request?.deferred?.complete(false)
                                r?.value?.onRequestHandled?.invoke()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Composable that observes [UnlockUseCase] and renders the SSH agent unlock
 * dialog content. When the vault transitions to [VaultState.Main], completes
 * the request's deferred with `true` and calls [onDismiss].
 */
@Composable
private fun SshAgentUnlockWindow(
    sshAgentRequestUiState: Loadable<SshAgentRequestUiState>,
    onDismiss: () -> Unit,
) {
    ManualAppScreen { vaultState ->
        val request = (sshAgentRequestUiState as? Loadable.Ok)
            ?.value
            ?.request
        LaunchedEffect(vaultState, request) {
            val getListRequest = request as? SshAgentGetListRequest
                ?: return@LaunchedEffect
            if (vaultState is VaultState.Main && getListRequest.deferred.complete(true)) {
                onDismiss()
            }
        }

        // Animate from 1f (full time remaining) to 0f (expired) over the
        // request's timeout duration.
        val timeoutProgress = remember { Animatable(1f) }
        val timeoutApplicable = sshAgentRequestUiState.getOrNull() != null
        LaunchedEffect(sshAgentRequestUiState) {
            val request = sshAgentRequestUiState.getOrNull()
                ?: return@LaunchedEffect
            timeoutProgress.snapTo(1f)
            timeoutProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = request.request.timeout.inWholeMilliseconds.toInt(),
                    easing = LinearEasing,
                ),
            )
            // Animation finished — timeout expired, auto-deny.
            request.request.deferred.complete(false)
            onDismiss()
        }

        when (vaultState) {
            is VaultState.Create -> ManualAppScreenOnCreate(vaultState)
            is VaultState.Unlock -> ManualAppScreenOnUnlock(vaultState)
            is VaultState.Loading -> ManualAppScreenOnLoading(vaultState)
            is VaultState.Main -> {
                // If the UI state is loading then just show the
                // loader interface.
                when (sshAgentRequestUiState) {
                    is Loadable.Loading -> ManualAppScreenOnLoading()
                    is Loadable.Ok -> {
                        val v = sshAgentRequestUiState.value
                        SshAgentRequestContent(
                            request = v.request,
                            onHandled = v.onRequestHandled,
                        )
                    }
                }
            }
        }

        // Timeout progress bar
        ExpandedIfNotEmpty(
            Unit.takeIf { timeoutApplicable },
        ) {
            LinearProgressIndicator(
                progress = { timeoutProgress.value },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SshAgentRequestContent(
    request: SshAgentRequest,
    onHandled: () -> Unit,
) = when (request) {
    is SshAgentApprovalRequest -> SshAgentApproveRequestContent(
        request = request,
        onHandled = onHandled,
    )

    is SshAgentGetListRequest -> SshAgentGetListRequestContent(
        request = request,
        onHandled = onHandled,
    )
}

@Composable
private fun SshAgentApproveRequestContent(
    request: SshAgentApprovalRequest,
    onHandled: () -> Unit,
) {
    SshAgentApprovalContent(
        request = request,
        onDismiss = onHandled,
    )
}

@Composable
private fun SshAgentGetListRequestContent(
    request: SshAgentGetListRequest,
    onHandled: () -> Unit,
) {
    SshAgentGetListContent(
        request = request,
        onDismiss = onHandled,
    )
}
