package com.artemchep.keyguard.common.service.hibp.passwords

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.data.pwnage.PasswordBreach

interface PasswordPwnageDataSourceLocal : PasswordPwnageDataSource {
    fun put(
        entity: PasswordBreach,
    ): IO<Unit>

    fun getOne(
        password: String,
    ): IO<PasswordBreach?>

    fun getMany(
        passwords: Collection<String>,
    ): IO<Map<String, PasswordBreach?>>

    fun clear(): IO<Unit>
}
