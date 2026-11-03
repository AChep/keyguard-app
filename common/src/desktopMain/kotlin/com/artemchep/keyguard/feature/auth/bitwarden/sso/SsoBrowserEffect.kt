package com.artemchep.keyguard.feature.auth.bitwarden.sso

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun SsoBrowserEffect(prompt: SsoBrowserPrompt) {
    val controller = LocalNavigationController.current
    val success = stringResource(Res.string.sso_browser_success)
    LaunchedEffect(prompt) {
        prompt.drive {
            receiveSsoLoopback(prompt, success) { controller.queue(NavigationIntent.NavigateToBrowser(it)) }
        }
    }
}
