package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.CheckPasswordSetLeakRequest
import com.artemchep.keyguard.common.model.PasswordPwnage
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageRepository
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CheckPasswordSetLeakImpl(
    private val passwordPwnageRepository: PasswordPwnageRepository,
) : CheckPasswordSetLeak {
    constructor(directDI: DirectDI) : this(
        passwordPwnageRepository = directDI.instance(),
    )

    override fun invoke(
        request: CheckPasswordSetLeakRequest,
    ): IO<Map<String, PasswordPwnage?>> = passwordPwnageRepository
        .checkMany(
            passwords = request.passwords,
            cache = request.cache,
        )
}
