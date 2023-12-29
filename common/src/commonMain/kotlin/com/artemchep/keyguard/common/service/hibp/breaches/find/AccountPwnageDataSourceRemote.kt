package com.artemchep.keyguard.common.service.hibp.breaches.find

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.UsernamePwnage

interface AccountPwnageDataSourceRemote : AccountPwnageDataSource {
    fun check(
        accountId: AccountId,
        username: String,
    ): IO<UsernamePwnage>
}
