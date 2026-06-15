package com.artemchep.keyguard.android.companion

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthError
import com.artemchep.keyguard.feature.localization.TextHolder

internal class CompanionAuthTransferException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause), Readable {
    override val title: TextHolder = TextHolder.Value(message)
}

internal suspend fun runCompanionTransfer(
    notifyError: (CompanionAuthError, String?) -> Unit,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()

        val message = e.message ?: "Failed to complete companion login."
        runCatching {
            notifyError(
                CompanionAuthError.REQUEST_FAILED,
                message,
            )
        }
        throw CompanionAuthTransferException(
            message = message,
            cause = e,
        )
    }
}
