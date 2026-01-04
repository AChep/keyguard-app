package com.artemchep.keyguard.common.exception.credential

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_credential_calling_app_not_privileged
import java.lang.IllegalStateException

class CallingAppNotPrivilegedException(
    e: Throwable? = null,
) : IllegalStateException("The calling app is not on the privileged list and can not request authentication on behalf of the other apps.", e),
    Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_credential_calling_app_not_privileged)
}