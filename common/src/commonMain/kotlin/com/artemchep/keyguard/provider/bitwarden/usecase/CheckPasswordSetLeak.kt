package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.exception.watchtower.PasswordPwnedDisabledException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.CheckPasswordSetLeakRequest
import com.artemchep.keyguard.common.model.PasswordPwnage
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageRepository
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords

/**
 * @author Artem Chepurnyi
 */
class CheckPasswordSetLeakImpl(
    private val passwordPwnageRepository: PasswordPwnageRepository,
    private val getCheckPwnedPasswords: GetCheckPwnedPasswords,
) : CheckPasswordSetLeak {
    override fun invoke(
        request: CheckPasswordSetLeakRequest,
    ): IO<Map<String, PasswordPwnage?>> = getCheckPwnedPasswords()
        .toIO()
        .flatMap { canCheckPwnedPassword ->
            if (!canCheckPwnedPassword) {
                val e = PasswordPwnedDisabledException()
                return@flatMap ioRaise(e)
            }

            performCheckPwnedPasswords(
                request = request,
            )
        }

    private fun performCheckPwnedPasswords(
        request: CheckPasswordSetLeakRequest,
    ) = passwordPwnageRepository
        .checkMany(
            passwords = request.passwords,
            cache = request.cache,
        )
}
