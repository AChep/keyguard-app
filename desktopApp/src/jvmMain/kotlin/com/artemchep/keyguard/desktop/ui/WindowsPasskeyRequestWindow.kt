package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.window.ApplicationScope
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.service.passkey.WindowsPasskeyService
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.passkeys
import kotlinx.coroutines.GlobalScope
import org.jetbrains.compose.resources.stringResource

/** Reads the request state here, so its transitions do not recompose the whole application. */
@Composable
internal fun ApplicationScope.WindowsPasskeyRequestHost(
    service: WindowsPasskeyService,
    processLifecycleProvider: LePlatformLifecycleProvider,
) {
    val state = service.ui.collectAsState().value ?: return
    // Existing vault/unlock screens run in a request-owned window, independently of the main window.
    PasskeyVaultWindow(
        requestId = state.request.id,
        title = stringResource(Res.string.passkeys),
        reason = state.request.rpId,
        expiresAt = state.expiresAt,
        mode = state.mode,
        focusTag = "WindowsPasskeys",
        processLifecycleProvider = processLifecycleProvider,
        onCancel = { service.cancel(state.request.id).launchIn(GlobalScope) },
    )
}
