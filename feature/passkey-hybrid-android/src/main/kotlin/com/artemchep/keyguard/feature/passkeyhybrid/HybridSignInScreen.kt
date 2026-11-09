package com.artemchep.keyguard.feature.passkeyhybrid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.feature.auth.userverification.UserVerificationRoute
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.screen.VaultListRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridPhase
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridSaveTarget
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridSessionState
import com.artemchep.keyguard.feature.qr.ScanQrRouteFactory
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.passkey_hybrid_save
import com.artemchep.keyguard.res.passkey_hybrid_scan_again
import com.artemchep.keyguard.res.passkey_hybrid_title
import com.artemchep.keyguard.res.scanqr_title
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabScope
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.wizard.WizardScaffold
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.currentKoinScope
import kotlin.uuid.Uuid

@Composable
internal fun HybridSignInScreen(state: HybridSessionState) {
    if (state.phase == HybridPhase.PERMISSION) {
        HybridBluetoothPermission(state.onPermission)
    }
    when (state.phase) {
        // Both own the whole surface, as they do in the passkey provider flow.
        HybridPhase.SAVE -> HybridSavePicker(state)
        HybridPhase.VERIFY -> HybridVerification(state)
        else -> HybridWizard(state)
    }
}

@Composable
private fun HybridWizard(state: HybridSessionState) {
    WizardScaffold(
        title = stringResource(Res.string.passkey_hybrid_title),
        // The producer replaces the state on every transition,
        // so each one cross-fades to a page of its own.
        step = state,
        navigationIcon = { NavigationIcon() },
        floatingActionState = rememberHybridFabState(state),
        floatingActionButton = { HybridFab(state) },
    ) { page ->
        HybridPage(page)
    }
}

/** Only scanning is a FAB; the other pages act from their own content. */
@Composable
private fun rememberHybridFabState(state: HybridSessionState): State<FabState?> {
    val scan = rememberHybridScanAction(state.onScan)
    val fabState =
        when (state.phase) {
            HybridPhase.SCAN,
            HybridPhase.ERROR,
            -> FabState(onClick = scan, model = null)

            else -> null
        }
    return rememberUpdatedState(fabState)
}

@Composable
private fun FabScope.HybridFab(state: HybridSessionState) {
    DefaultFab(
        icon = {
            Icon(Icons.Outlined.QrCodeScanner, null)
        },
        text = {
            val res =
                if (state.phase == HybridPhase.ERROR) {
                    Res.string.passkey_hybrid_scan_again
                } else {
                    Res.string.scanqr_title
                }
            Text(stringResource(res))
        },
    )
}

/** Opens the QR scanner, or `null` while there is nothing to scan for or no scanner. */
@Composable
private fun rememberHybridScanAction(onScan: ((String) -> Unit)?): (() -> Unit)? {
    val controller = LocalNavigationController.current
    val koin = currentKoinScope()
    val scanner = remember(koin) { koin.getOrNull<ScanQrRouteFactory>() }
    val currentOnScan by rememberUpdatedState(onScan)
    val action =
        remember(controller, scanner) {
            scanner?.let {
                {
                    val route =
                        registerRouteResultReceiver(it.create()) { raw ->
                            controller.queue(NavigationIntent.Pop)
                            currentOnScan?.invoke(raw)
                        }
                    controller.queue(NavigationIntent.NavigateToRoute(route))
                }
            }
        }
    return action.takeIf { onScan != null }
}

@Composable
private fun HybridBluetoothPermission(onResult: ((Boolean) -> Unit)?) {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val permissions =
        remember {
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyArray()
            }
        }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            currentOnResult?.invoke(permissions.all { permission -> it[permission] == true })
        }
    LaunchedEffect(Unit) {
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            currentOnResult?.invoke(true)
        } else {
            launcher.launch(permissions)
        }
    }
}

@Composable
private fun HybridVerification(state: HybridSessionState) {
    val onVerified by rememberUpdatedState(state.onVerified)
    val route = remember { UserVerificationRoute { onVerified?.invoke() } }
    NavigationNode(id = "hybrid_verification", route = route)
}

@Composable
private fun HybridSavePicker(state: HybridSessionState) {
    val onSave by rememberUpdatedState(state.onSaveTarget)
    val mode =
        remember(state.rpId) {
            AppMode.SavePasskey(requireNotNull(state.rpId)) { cipher ->
                onSave?.invoke(HybridSaveTarget(cipher.accountId, cipher.id))
            }
        }
    val title = stringResource(Res.string.passkey_hybrid_save, state.rpId.orEmpty())
    val route =
        remember(title, state.username) {
            VaultListRoute(
                VaultRoute.Args(
                    appBar =
                        VaultRoute.Args.AppBar(
                            title = title,
                            subtitle = state.username?.let(TextHolder::Value),
                        ),
                ),
            )
        }
    // The parent entry keeps a router's stack and its vault list state, which captured the
    // previous request's mode. A new request needs a new stack.
    val routerId = remember { "hybrid_save_${Uuid.random()}" }
    CompositionLocalProvider(LocalAppMode provides mode) {
        NavigationRouter(id = routerId, initial = route) { entries ->
            NavigationNode(entries = entries)
        }
    }
}
