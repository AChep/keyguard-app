package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.CheckPasswordLeakRequest
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageRepository
import com.artemchep.keyguard.common.usecase.CheckPasswordLeak
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CheckPasswordLeakImpl(
    private val passwordPwnageRepository: PasswordPwnageRepository,
) : CheckPasswordLeak {
    constructor(directDI: DirectDI) : this(
        passwordPwnageRepository = directDI.instance(),
    )

    override fun invoke(
        request: CheckPasswordLeakRequest,
    ): IO<Int> = passwordPwnageRepository
        .checkOne(
            password = request.password,
            cache = request.cache,
        )
        .map {
            it.occurrences
        }

}
