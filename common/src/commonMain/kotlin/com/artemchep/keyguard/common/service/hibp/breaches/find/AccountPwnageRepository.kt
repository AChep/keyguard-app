package com.artemchep.keyguard.common.service.hibp.breaches.find

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.UsernamePwnage

interface AccountPwnageRepository {
    fun checkOne(
        accountId: AccountId,
        username: String,
        cache: Boolean,
    ): IO<UsernamePwnage>

    fun checkMany(
        accountId: AccountId,
        usernames: Set<String>,
        cache: Boolean,
    ): IO<Map<String, UsernamePwnage?>>
}
