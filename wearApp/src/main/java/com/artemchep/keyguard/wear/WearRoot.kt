package com.artemchep.keyguard.wear

import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.feature.home.HomeLayout
import com.artemchep.keyguard.feature.home.LocalHomeLayout
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.platform.leIme
import com.artemchep.keyguard.platform.leNavigationBars
import com.artemchep.keyguard.ui.ToastMessage
import com.artemchep.keyguard.ui.ToastMessageHost
import com.artemchep.keyguard.wear.feature.WearCreateVaultScreen
import com.artemchep.keyguard.wear.feature.WearLoadingScreen
import com.artemchep.keyguard.wear.feature.WearUnlockVaultScreen
import com.artemchep.keyguard.wear.feature.home.WearHomeRoute
import org.kodein.di.compose.withDI

@Composable
fun WearRoot() {
    CompositionLocalProvider(
        LocalAppMode provides AppMode.Main,
        LocalHomeLayout provides HomeLayout.Vertical,
    ) {
        NavigationNode(
            id = "App:Main",
            route = WearAppRoute,
        )
    }
}

private object WearAppRoute : Route {
    @Composable
    override fun Content() {
        WearAppState()
    }
}

@Composable
private fun WearAppState() {
    ManualAppScreen(
        toastHost = {
            WearToastHost()
        },
    ) { state ->
        when (state) {
            is VaultState.Create -> WearCreateVaultScreen(state)
            is VaultState.Unlock -> WearUnlockVaultScreen(state)
            is VaultState.Main -> WearMainAppScreen(state)

            VaultState.Loading -> WearLoadingScreen()
        }
    }
}

@Composable
private fun WearMainAppScreen(
    state: VaultState.Main,
) {
    // Provide the session DI to all the
    // sub screens of this composable.
    withDI(state.di) {
        NavigationRouter(
            id = "home",
            initial = WearHomeRoute,
        ) { backStack ->
            NavigationNode(
                entries = backStack,
            )
        }
    }
}

@Composable
private fun BoxScope.WearToastHost() {
    val insets = WindowInsets.leNavigationBars
        .union(WindowInsets.leIme)
        .asPaddingValues()
    ToastMessageHost(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(insets)
            .widthIn(max = 300.dp),
        itemEnterTransition = fadeIn() +
                slideIn(initialOffset = { IntOffset(0, it.height) }) +
                expandIn(initialSize = { IntSize(it.width, 0) }),
        itemExitTransition = fadeOut() +
                slideOut(targetOffset = { IntOffset(0, it.height) }) +
                shrinkOut(targetSize = { IntSize(it.width, 0) }),
        item = { modifier, msg ->
            ToastMessage(
                modifier = modifier,
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 16.dp,
                ),
                model = msg,
            )
        },
        content = { modifier, items ->
            Column(
                modifier = modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items.forEach { item ->
                    key(item.drawable.key) {
                        item.drawable.Content()
                    }
                }
            }
        },
    )
}
