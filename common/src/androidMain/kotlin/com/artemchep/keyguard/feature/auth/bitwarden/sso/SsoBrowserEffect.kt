package com.artemchep.keyguard.feature.auth.bitwarden.sso

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun SsoBrowserEffect(prompt: SsoBrowserPrompt) {
    val context = LocalContext.current
    // Resolved once per screen, not per attempt.
    val authTabProvider = remember(context) {
        CustomTabsClient.getPackageName(context, emptyList())
            ?.takeIf { CustomTabsClient.isAuthTabSupported(context, it) }
    }
    key(prompt) { SsoBrowserContent(prompt, authTabProvider) }
}

@Composable
private fun SsoBrowserContent(prompt: SsoBrowserPrompt, authTabProvider: String?) {
    val controller = LocalNavigationController.current
    val success = stringResource(Res.string.sso_browser_success)
    val launcher = rememberLauncherForActivityResult(AuthTabIntent.AuthenticateUserResultContract()) { result ->
        prompt.finish(
            callback = result.resultUri?.toString()
                .takeIf { result.resultCode == AuthTabIntent.RESULT_OK },
            failed = result.resultCode != AuthTabIntent.RESULT_CANCELED,
        )
    }
    LaunchedEffect(prompt) {
        prompt.drive {
            if (authTabProvider != null) {
                val intent = AuthTabIntent.Builder().build()
                intent.intent.setPackage(authTabProvider)
                intent.launch(launcher, Uri.parse(prompt.request.browserUrl(SsoRequest.NATIVE_CALLBACK)), "bitwarden")
                prompt.result.await()
            } else {
                receiveSsoLoopback(prompt, success) { controller.queue(NavigationIntent.NavigateToBrowser(it)) }
            }
        }
    }
}
