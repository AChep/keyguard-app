package com.artemchep.keyguard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.artemchep.keyguard.feature.keyguard.AppScreen
import com.artemchep.keyguard.feature.navigation.LocalNavigationBackHandler
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationRouterBackHandler
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LocalWindowId
import com.artemchep.keyguard.platform.WindowId
import com.artemchep.keyguard.platform.leIme
import com.artemchep.keyguard.platform.leNavigationBars
import com.artemchep.keyguard.platform.theme.hasDarkThemeEnabled
import com.artemchep.keyguard.ui.ToastMessageHost
import com.artemchep.keyguard.ui.surface.ProvideSurfaceColor
import com.artemchep.keyguard.ui.theme.KeyguardTheme
import com.artemchep.keyguard.ui.theme.ThemeConfig
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DI
import org.kodein.di.compose.withDI
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    withDI(iosAppDi) {
        KeyguardIosApp()
    }
}

private val iosAppDi by lazy {
    createIosAppDi()
}

private fun createIosAppDi(): DI {
    return DI {
        installIosAppModule(
        )
    }
}

@Composable
private fun KeyguardIosApp() {
    val dark = CurrentPlatform.hasDarkThemeEnabled()
    KeyguardTheme(
        config = ThemeConfig(
            expressive = true,
            colors = null,
            mode = null,
            useAmoledBlack = false,
        ),
        dark = dark,
    ) {
        val surfaceColor = MaterialTheme.colorScheme.background
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = surfaceColor,
        ) {
            ProvideSurfaceColor(surfaceColor) {
                CompositionLocalProvider(
                    LocalAppMode provides AppMode.Main,
                    LocalWindowId provides WindowId(1L),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        KeyguardIosNavigation()
                        KeyguardIosToastHost()
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyguardIosNavigation() = NavigationRouterBackHandler(
    sideEffect = {
    },
) {
    val scope = rememberCoroutineScope()
    NavigationController(
        scope = scope,
        canPop = flowOf(false),
        handle = { null },
    ) { controller ->
        val backHandler = LocalNavigationBackHandler.current
        DisposableEffect(
            controller,
            backHandler,
        ) {
            val registration = backHandler.register(controller, emptyList())
            onDispose {
                registration()
            }
        }

        AppScreen()
    }
}

@Composable
private fun BoxScope.KeyguardIosToastHost() {
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
