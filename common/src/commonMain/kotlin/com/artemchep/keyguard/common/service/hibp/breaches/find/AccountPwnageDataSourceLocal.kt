package com.artemchep.keyguard.common.service.hibp.breaches.find

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.data.pwnage.AccountBreach

interface AccountPwnageDataSourceLocal : AccountPwnageDataSource {
    fun put(
        entity: AccountBreach,
    ): IO<Unit>

    fun getOne(
        username: String,
    ): IO<AccountBreach?>

    fun getMany(
        usernames: Collection<String>,
    ): IO<Map<String, AccountBreach?>>

    fun clear(): IO<Unit>
}
