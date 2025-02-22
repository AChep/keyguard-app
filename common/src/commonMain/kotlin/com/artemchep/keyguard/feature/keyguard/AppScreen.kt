package com.artemchep.keyguard.feature.keyguard

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.feature.keyguard.main.MainRoute
import com.artemchep.keyguard.feature.keyguard.setup.SetupRoute
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockRoute
import com.artemchep.keyguard.feature.loading.LoadingScreen
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.platform.leIme
import com.artemchep.keyguard.platform.leNavigationBars
import com.artemchep.keyguard.platform.lifecycle.LocalLifecycleStateFlow
import com.artemchep.keyguard.platform.lifecycle.flowWithLifecycle
import com.artemchep.keyguard.ui.ToastMessageHost
import org.kodein.di.compose.rememberInstance
import org.kodein.di.compose.withDI

@Composable
fun AppScreen() {
    ManualAppScreen { vaultState ->
        when (vaultState) {
            is VaultState.Create -> ManualAppScreenOnCreate(vaultState)
            is VaultState.Unlock -> ManualAppScreenOnUnlock(vaultState)
            is VaultState.Loading -> ManualAppScreenOnLoading(vaultState)
            is VaultState.Main -> ManualAppScreenOnMain(vaultState)
        }
    }
}

//
// Internals
//

@Composable
fun ManualAppScreenOnCreate(
    state: VaultState.Create,
) {
    val route = remember(state) {
        SetupRoute(
            createVaultWithMasterPassword = state.createWithMasterPassword,
            createVaultWithMasterPasswordAndBiometric = state.createWithMasterPasswordAndBiometric,
        )
    }
    NavigationNode(
        id = "setup",
        route = route,
    )
}

@Composable
fun ManualAppScreenOnUnlock(
    state: VaultState.Unlock,
) {
    val route = remember(state) {
        UnlockRoute(
            unlockVaultByMasterPassword = state.unlockWithMasterPassword,
            unlockVaultByBiometric = state.unlockWithBiometric,
            lockInfo = state.lockInfo,
        )
    }
    NavigationNode(
        id = "unlock",
        route = route,
    )
}

@Composable
fun ManualAppScreenOnLoading(
    state: VaultState.Loading,
) {
    LoadingScreen()
}

@Composable
fun ManualAppScreenOnMain(
    state: VaultState.Main,
) {
    // Provide the session DI to all of the
    // sub screens of this composable.
    withDI(state.di) {
        NavigationNode(
            id = "main",
            route = MainRoute,
        )
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ManualAppScreen(
    content: @Composable (VaultState) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        var appState by remember {
            mutableStateOf<VaultState>(VaultState.Loading)
        }

        val appViewModel by rememberInstance<UnlockUseCase>()
        val lifecycleFlow = LocalLifecycleStateFlow
        LaunchedEffect(appViewModel, lifecycleFlow) {
            appViewModel()
                .flowWithLifecycle(lifecycleFlow)
                .collect { state ->
                    if (state != appState) {
                        appState = state
                    }
                }
        }

        val transition = updateTransition(appState)
        transition.Crossfade(
            modifier = Modifier,
            animationSpec = tween(),
            contentKey = { it::class },
        ) { state ->
            content(state)
        }

        val insets = WindowInsets.leNavigationBars
            .union(WindowInsets.leIme)
            .asPaddingValues()
        ToastMessageHost(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(insets)
                .widthIn(max = 300.dp),
        )
    }
}
