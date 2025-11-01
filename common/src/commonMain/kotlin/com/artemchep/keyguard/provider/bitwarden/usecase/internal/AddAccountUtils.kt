package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.usecase.GetAccounts

object AddAccountUtils {
    fun getPremiumAddAccountPredicateIo(
        getAccounts: GetAccounts,
    ) = getAccounts()
        .toIO()
        .map { accounts -> accounts.size > 1 }
}
