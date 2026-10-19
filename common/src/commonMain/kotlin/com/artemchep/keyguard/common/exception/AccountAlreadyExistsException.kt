package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

class AccountAlreadyExistsException : IllegalStateException("The account is already added"), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_account_already_exists)
}
