package com.artemchep.keyguard.desktop.util

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.loading.ReadableExceptionMessage
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_failed_open_app_for
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

private const val NAV_TAG = "Navigation"

internal fun handleNavigationIntent(
    exitApplication: () -> Unit,
    intent: NavigationIntent,
    showMessage: ShowMessage,
    translatorScope: TranslatorScope,
    logRepository: LogRepository,
    scope: CoroutineScope,
) = runCatching {
    when (intent) {
        is NavigationIntent.NavigateToPreview -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToPreviewInFileManager -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToSend -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToLargeType -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToShare -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToApp -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToPhone -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToSms -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToEmail -> handleNavigationIntent(intent, showMessage)
        is NavigationIntent.NavigateToBrowser -> handleNavigationIntent(intent, showMessage)
        // Should never be called, because we should disable
        // custom back button handling if we have nothing to
        // handle.
        is NavigationIntent.Pop -> {
            val msg = "Called Activity.finish() manually. We should have stopped " +
                    "intercepting back button presses."
            exitApplication()
        }
        // Exit.
        is NavigationIntent.Exit -> {
            exitApplication()
        }

        else -> return@runCatching intent
    }
    null // handled
}.onFailure { e ->
    showMessage.internalShowNavigationErrorMessage(
        e = e,
        intent = intent,
        translatorScope = translatorScope,
        logRepository = logRepository,
        scope = scope,
    )
}.getOrNull()

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToPreview,
    showMessage: ShowMessage,
) {
    navigateToFile(
        uri = intent.uri,
    )
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToPreviewInFileManager,
    showMessage: ShowMessage,
) {
    navigateToFileInFileManager(
        uri = intent.uri,
    )
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToSend,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToLargeType,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToShare,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToApp,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToPhone,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToSms,
    showMessage: ShowMessage,
) {
    TODO()
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToEmail,
    showMessage: ShowMessage,
) {
    navigateToEmail(
        email = intent.email,
        subject = intent.subject,
        body = intent.body,
    )
}

private fun handleNavigationIntent(
    intent: NavigationIntent.NavigateToBrowser,
    showMessage: ShowMessage,
) {
    navigateToBrowser(
        uri = intent.url,
    )
}

private fun ShowMessage.internalShowNavigationErrorMessage(
    e: Throwable,
    intent: NavigationIntent,
    translatorScope: TranslatorScope,
    logRepository: LogRepository,
    scope: CoroutineScope,
) {
    recordException(e)
    // Keep a copy in the in-app logs, so a user can
    // report the failure without having to launch the
    // app from a terminal.
    logRepository.post(
        tag = NAV_TAG,
        message = "Failed to handle ${intent::class.simpleName}: $e",
        level = LogLevel.ERROR,
    )

    scope.launch {
        val msg = when (e) {
            // Thrown when the AWT Desktop API is not available or the
            // fallback command could not be launched. Both mean that
            // we did not find anything to handle the request with.
            is UnsupportedOperationException,
            is IOException,
            -> {
                val title = translatorScope.translate(Res.string.error_failed_open_app_for)
                ReadableExceptionMessage(
                    title = title,
                    text = e.message,
                )
            }

            else -> getErrorReadableMessage(e, translatorScope)
        }

        val model = ToastMessage(
            type = ToastMessage.Type.ERROR,
            title = msg.title,
            text = msg.text,
        )
        copy(model)
    }
}
