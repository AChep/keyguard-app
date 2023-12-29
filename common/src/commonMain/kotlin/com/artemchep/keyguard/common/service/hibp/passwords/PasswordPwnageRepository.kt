package com.artemchep.keyguard.common.service.hibp.passwords

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.PasswordPwnage

interface PasswordPwnageRepository {
    fun checkOne(
        password: String,
        cache: Boolean,
    ): IO<PasswordPwnage>

    fun checkMany(
        passwords: Set<String>,
        cache: Boolean,
    ): IO<Map<String, PasswordPwnage?>>
}
