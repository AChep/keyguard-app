package com.artemchep.keyguard.common.service.hibp.passwords

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.PasswordPwnage

interface PasswordPwnageDataSourceRemote : PasswordPwnageDataSource {
    fun check(
        password: String,
    ): IO<PasswordPwnage>
}
