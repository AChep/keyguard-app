package com.artemchep.keyguard.feature.fido2

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.util.fido2.Fido2Exception
import com.artemchep.keyguard.util.fido2.Fido2Failure

fun Throwable.asFido2AppException(): Throwable =
    if (this is Readable) this else Fido2AppException(this)

private class Fido2AppException(cause: Throwable) :
    RuntimeException("Security key operation failed", cause), Readable {
    override val title = TextHolder.Res(Res.string.fido2_error_title)
    override val text =
        TextHolder.Res(
            when ((cause as? Fido2Exception)?.failure) {
                Fido2Failure.UNSUPPORTED -> Res.string.fido2_error_unsupported
                Fido2Failure.PIN_NOT_SET -> Res.string.fido2_error_pin_not_set
                Fido2Failure.PIN_BLOCKED -> Res.string.fido2_error_pin_blocked
                Fido2Failure.TIMEOUT -> Res.string.fido2_error_timeout
                else -> Res.string.fido2_error_retry
            }
        )
}
