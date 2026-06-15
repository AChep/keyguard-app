package com.artemchep.keyguard.wear

import android.content.Context
import android.content.Intent
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.navigation.LocalNavigationBackHandler
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationRouterBackHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.rememberInstance

/**
 * Shared navigation host for Wear activities.
 *
 * Sets up [NavigationRouterBackHandler] and [NavigationController] with
 * browser-intent handling, reducing boilerplate duplicated across
 * [WearActivity] and [WearCredentialProviderActivity].
 */
@Composable
fun WearNavigationHost(
    onBackPressedDispatcher: OnBackPressedDispatcher,
    scope: CoroutineScope,
    content: @Composable () -> Unit,
) = NavigationRouterBackHandler(
    onBackPressedDispatcher = onBackPressedDispatcher,
) {
    val context = LocalContext.current
    val showMessage by rememberInstance<ShowMessage>()
    NavigationController(
        scope = scope,
        canPop = flowOf(false),
        handle = { intent ->
            handleWearNavigationIntent(
                context = context,
                intent = intent,
                showMessage = showMessage,
            )
        },
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

        content()
    }
}

/**
 * Handles navigation intents common to all Wear activities.
 *
 * Returns the intent unchanged if not handled, or `null` if consumed.
 */
private fun handleWearNavigationIntent(
    context: Context,
    intent: NavigationIntent,
    showMessage: ShowMessage,
): NavigationIntent? = when (intent) {
    is NavigationIntent.NavigateToBrowser -> {
        launchBrowser(
            context = context,
            url = intent.url,
            showMessage = showMessage,
        )
        null
    }

    else -> intent
}

/**
 * Opens a URL in the system browser, showing an error toast on failure.
 */
private fun launchBrowser(
    context: Context,
    url: String,
    showMessage: ShowMessage,
) {
    kotlin.runCatching {
        Intent(Intent.ACTION_VIEW, url.toUri())
            .let(context::startActivity)
    }.onFailure { e ->
        val message = ToastMessage(
            type = ToastMessage.Type.ERROR,
            title = e.message ?: "Failed to open link.",
        )
        showMessage.copy(message)
    }
}
