package com.artemchep.keyguard.desktop.util

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.navigation.NavigationIntent

internal fun handleNavigationIntent(
    exitApplication: () -> Unit,
    intent: NavigationIntent,
    showMessage: ShowMessage,
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
    showMessage.internalShowNavigationErrorMessage(e)
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

private fun ShowMessage.internalShowNavigationErrorMessage(e: Throwable) {
    e.printStackTrace()

    val model = ToastMessage(
        type = ToastMessage.Type.ERROR,
        title = when (e) {
            else -> "Something went wrong"
        },
    )
    copy(model)
}
