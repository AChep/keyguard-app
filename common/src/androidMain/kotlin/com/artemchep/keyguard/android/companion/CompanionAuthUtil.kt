package com.artemchep.keyguard.android.companion

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthError
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_failed_unknown

internal class CompanionAuthTransferException(
    message: String?,
    cause: Throwable,
) : IllegalStateException(message, cause), Readable {
    override val title: TextHolder = message?.let(TextHolder::Value)
        ?: Res.string.error_failed_unknown.wrap()
}

internal suspend fun runCompanionTransfer(
    notifyError: (CompanionAuthError, String?) -> Unit,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()

        val message = e.message
        runCatchingNonFatal {
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
