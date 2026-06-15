package com.artemchep.keyguard.feature.auth.companion

sealed interface CompanionAuthRequestState {
    data object WaitingForPhone : CompanionAuthRequestState

    data object Importing : CompanionAuthRequestState

    data class Failed(
        val error: CompanionAuthError,
        val message: String? = null,
    ) : CompanionAuthRequestState

    data class Cancelled(
        val message: String? = null,
    ) : CompanionAuthRequestState

    data object Success : CompanionAuthRequestState
}

sealed interface CompanionAuthRequestEvent {
    data object RequestStarted : CompanionAuthRequestEvent

    data class RemoteLaunchFailed(
        val message: String? = null,
    ) : CompanionAuthRequestEvent

    data class TimedOut(
        val message: String? = null,
    ) : CompanionAuthRequestEvent

    data class PhoneCancelled(
        val message: String? = null,
    ) : CompanionAuthRequestEvent

    data class PhoneErrored(
        val error: CompanionAuthError,
        val message: String? = null,
    ) : CompanionAuthRequestEvent

    data object ImportStarted : CompanionAuthRequestEvent

    data object ImportSucceeded : CompanionAuthRequestEvent

    data class ImportFailed(
        val message: String? = null,
    ) : CompanionAuthRequestEvent

    data class InvalidRequest(
        val message: String? = null,
    ) : CompanionAuthRequestEvent
}

fun reduceCompanionAuthRequestState(
    current: CompanionAuthRequestState?,
    event: CompanionAuthRequestEvent,
): CompanionAuthRequestState? {
    if (current.isTerminal()) {
        return current
    }

    return when (event) {
        CompanionAuthRequestEvent.RequestStarted -> when (current) {
            null,
            CompanionAuthRequestState.WaitingForPhone,
            -> CompanionAuthRequestState.WaitingForPhone

            CompanionAuthRequestState.Importing -> current
            is CompanionAuthRequestState.Cancelled,
            is CompanionAuthRequestState.Failed,
            CompanionAuthRequestState.Success,
            -> current
        }

        is CompanionAuthRequestEvent.RemoteLaunchFailed -> when (current) {
            null,
            CompanionAuthRequestState.WaitingForPhone,
            -> CompanionAuthRequestState.Failed(
                error = CompanionAuthError.LAUNCH_FAILED,
                message = event.message,
            )

            CompanionAuthRequestState.Importing -> current
            is CompanionAuthRequestState.Cancelled,
            is CompanionAuthRequestState.Failed,
            CompanionAuthRequestState.Success,
            -> current
        }

        is CompanionAuthRequestEvent.TimedOut -> when (current) {
            null,
            CompanionAuthRequestState.WaitingForPhone,
            -> CompanionAuthRequestState.Failed(
                error = CompanionAuthError.TIMEOUT,
                message = event.message,
            )

            CompanionAuthRequestState.Importing -> current
            is CompanionAuthRequestState.Cancelled,
            is CompanionAuthRequestState.Failed,
            CompanionAuthRequestState.Success,
            -> current
        }

        is CompanionAuthRequestEvent.PhoneCancelled -> when (current) {
            null,
            CompanionAuthRequestState.WaitingForPhone,
            -> CompanionAuthRequestState.Cancelled(
                message = event.message,
            )

            CompanionAuthRequestState.Importing -> current
            is CompanionAuthRequestState.Cancelled,
            is CompanionAuthRequestState.Failed,
            CompanionAuthRequestState.Success,
            -> current
        }

        is CompanionAuthRequestEvent.PhoneErrored -> when (current) {
            null,
            CompanionAuthRequestState.WaitingForPhone,
            -> CompanionAuthRequestState.Failed(
                error = event.error,
                message = event.message,
            )

            CompanionAuthRequestState.Importing -> current
            is CompanionAuthRequestState.Cancelled,
            is CompanionAuthRequestState.Failed,
            CompanionAuthRequestState.Success,
            -> current
        }

        CompanionAuthRequestEvent.ImportStarted -> when (current) {
            null,
            CompanionAuthRequestState.WaitingForPhone,
            CompanionAuthRequestState.Importing,
            -> CompanionAuthRequestState.Importing

            is CompanionAuthRequestState.Cancelled,
            is CompanionAuthRequestState.Failed,
            CompanionAuthRequestState.Success,
            -> current
        }

        CompanionAuthRequestEvent.ImportSucceeded -> when (current) {
            null,
            CompanionAuthRequestState.WaitingForPhone,
            CompanionAuthRequestState.Importing,
            -> CompanionAuthRequestState.Success

            is CompanionAuthRequestState.Cancelled,
            is CompanionAuthRequestState.Failed,
            CompanionAuthRequestState.Success,
            -> current
        }

        is CompanionAuthRequestEvent.ImportFailed -> when (current) {
            null,
            CompanionAuthRequestState.WaitingForPhone,
            CompanionAuthRequestState.Importing,
            -> CompanionAuthRequestState.Failed(
                error = CompanionAuthError.IMPORT_FAILED,
                message = event.message,
            )

            is CompanionAuthRequestState.Cancelled,
            is CompanionAuthRequestState.Failed,
            CompanionAuthRequestState.Success,
            -> current
        }

        is CompanionAuthRequestEvent.InvalidRequest -> when (current) {
            null,
            CompanionAuthRequestState.WaitingForPhone,
            CompanionAuthRequestState.Importing,
            -> CompanionAuthRequestState.Failed(
                error = CompanionAuthError.INVALID_REQUEST,
                message = event.message,
            )

            is CompanionAuthRequestState.Cancelled,
            is CompanionAuthRequestState.Failed,
            CompanionAuthRequestState.Success,
            -> current
        }
    }
}

fun CompanionAuthRequestState?.isTerminal(): Boolean = when (this) {
    is CompanionAuthRequestState.Cancelled,
    is CompanionAuthRequestState.Failed,
    CompanionAuthRequestState.Success,
    -> true

    CompanionAuthRequestState.Importing,
    CompanionAuthRequestState.WaitingForPhone,
    null,
    -> false
}
