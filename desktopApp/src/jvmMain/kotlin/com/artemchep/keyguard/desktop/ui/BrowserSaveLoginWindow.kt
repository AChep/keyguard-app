package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ApplicationScope
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.browser.BrowserSaveRequest
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.di.VaultSessionContent
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.LeAddRoute
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_save_window_title
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Shows a window for each login that a browser extension asks to save.
 * Reads the requests here, so their transitions do not recompose the whole
 * application.
 */
@Composable
internal fun ApplicationScope.BrowserSaveLoginHost(
    service: BrowserExtensionService,
    processLifecycleProvider: LePlatformLifecycleProvider,
) {
    val saves by service.saves.collectAsState()
    saves.forEach { request ->
        key(request.id) {
            BrowserSaveLoginWindow(
                request = request,
                processLifecycleProvider = processLifecycleProvider,
                onShown = { service.onSaveShown(request.id) },
                onClose = { service.closeSave(request.id) },
            )
        }
    }
}

/**
 * The editor of the login, in a request-owned window independent of the
 * main window. The user saves the login, or closes the window.
 */
@Composable
private fun ApplicationScope.BrowserSaveLoginWindow(
    request: BrowserSaveRequest,
    processLifecycleProvider: LePlatformLifecycleProvider,
    onShown: () -> Unit,
    onClose: () -> Unit,
) {
    VaultRequestWindow(
        requestId = request.id,
        title = stringResource(Res.string.browser_extension_save_window_title),
        reason = request.webDomain,
        focusTag = "BrowserSaveLogin",
        processLifecycleProvider = processLifecycleProvider,
        onCancel = onClose,
        onShown = onShown,
    ) { vault ->
        VaultSessionContent(vault.session) {
            CompositionLocalProvider(LocalAppMode provides AppMode.Main) {
                BrowserSaveLoginEditor(request)
            }
        }
    }
}

/** The editor of a new login, or of the login to update with the new password. */
@Composable
private fun BrowserSaveLoginEditor(request: BrowserSaveRequest) {
    val getCiphers = koinInject<GetCiphers>()
    val existingState by produceState<Loadable<DSecret?>>(Loadable.Loading, request.cipherId, getCiphers) {
        val cipherId = request.cipherId
        val existing = cipherId?.let { id ->
            getCiphers()
                .first()
                .firstOrNull { it.id == id }
        }
        value = Loadable.Ok(existing)
    }
    val existing = when (val state = existingState) {
        is Loadable.Loading -> {
            ManualAppScreenOnLoading()
            return
        }

        is Loadable.Ok -> state.value
    }
    val route = remember(request.id, existing) {
        LeAddRoute(
            args = AddRoute.Args(
                type = DSecret.Type.Login,
                // The login of the same site and username: its password
                // and website are replaced by the ones of the page.
                initialValue = existing,
                autofill = AddRoute.Args.Autofill(
                    webDomain = request.webDomain,
                    webScheme = request.webScheme,
                    username = request.username,
                    password = request.password,
                ),
                behavior = AddRoute.Args.Behavior(
                    launchEditedCipher = false,
                ),
            ),
        )
    }
    // A stack of its own: popping the editor closes the window.
    val root = remember(request.id, route) {
        object : Route {
            @Composable
            override fun Content() {
                NavigationRouter(id = "browser_save.${request.id}", initial = route) { entries ->
                    NavigationNode(entries = entries)
                }
            }
        }
    }
    NavigationNode(id = "App:BrowserSave:${request.id}", route = root)
}
