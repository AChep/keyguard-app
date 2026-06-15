package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.exception.watchtower.PasswordPwnedDisabledException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.CheckPasswordLeakRequest
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageRepository
import com.artemchep.keyguard.common.usecase.CheckPasswordLeak
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CheckPasswordLeakImpl(
    private val passwordPwnageRepository: PasswordPwnageRepository,
    private val getCheckPwnedPasswords: GetCheckPwnedPasswords,
) : CheckPasswordLeak {
    constructor(directDI: DirectDI) : this(
        passwordPwnageRepository = directDI.instance(),
        getCheckPwnedPasswords = directDI.instance(),
    )

    override fun invoke(
        request: CheckPasswordLeakRequest,
    ): IO<Int> = getCheckPwnedPasswords()
        .toIO()
        .flatMap { canCheckPwnedPassword ->
            if (!canCheckPwnedPassword) {
                val e = PasswordPwnedDisabledException()
                return@flatMap ioRaise(e)
            }

            performCheckPwnedPassword(
                request = request,
            )
        }

    private fun performCheckPwnedPassword(
        request: CheckPasswordLeakRequest,
    ) = passwordPwnageRepository
        .checkOne(
            password = request.password,
            cache = request.cache,
        )
        .map {
            it.occurrences
        }

}
