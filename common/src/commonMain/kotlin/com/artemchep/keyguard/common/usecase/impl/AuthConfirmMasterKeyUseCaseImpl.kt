package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.PasswordMismatchException
import com.artemchep.keyguard.common.exception.UnsupportedMasterKdfVersionException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.handleErrorWith
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterKeyUseCase
import com.artemchep.keyguard.util.foundation.constantTimeEquals
import kotlinx.coroutines.Dispatchers

class AuthConfirmMasterKeyUseCaseImpl(
    private val generateMasterHashUseCase: GenerateMasterHashUseCase,
    private val generateMasterKeyUseCase: GenerateMasterKeyUseCase,
    private val masterKeyWrapping: MasterKeyWrapping,
) : AuthConfirmMasterKeyUseCase {
    override fun invoke(
        token: FingerprintPassword,
    ) = { password: MasterPassword ->
        when (token) {
            is FingerprintPassword.Derived -> confirmDerived(token, password)
            is FingerprintPassword.Wrapped -> confirmWrapped(token, password)
        }
    }

    private fun confirmDerived(
        token: FingerprintPassword.Derived,
        password: MasterPassword,
    ): IO<AuthResult> = generateMasterHashUseCase
        .verifyPassword(token, password)
        .flatMap {
            generateMasterKeyUseCase(password, token.hash)
        }
        .handleErrorWith(
            predicate = { e -> e is UnsupportedMasterKdfVersionException },
        ) {
            ioRaise(PasswordMismatchException())
        }
        .map { key ->
            AuthResult(
                key = key,
                token = token,
            )
        }

    private fun confirmWrapped(
        token: FingerprintPassword.Wrapped,
        password: MasterPassword,
    ): IO<AuthResult> = ioEffect(Dispatchers.Default) {
        // A key derivation this build can not read
        // never opens the vault.
        val kdf = token.kdf as? MasterKdf.Argon2id
            ?: throw PasswordMismatchException()
        val key = masterKeyWrapping.unwrap(
            password = password.byteArray,
            salt = token.salt.byteArray,
            kdf = kdf,
            wrapped = token.wrappedKey,
        )
        AuthResult(
            key = MasterKey(
                version = token.version,
                byteArray = key,
            ),
            token = token,
        )
    }
}

/**
 * Verifies that the [password] gives the master hash of the [token].
 * Only the hash stage runs: the master key is not derived.
 */
internal fun GenerateMasterHashUseCase.verifyPassword(
    token: FingerprintPassword.Derived,
    password: MasterPassword,
): IO<Unit> = invoke(password, token.salt, token.kdf)
    .flatMap { hash ->
        if (hash.byteArray.constantTimeEquals(token.hash.byteArray)) {
            io(Unit)
        } else {
            ioRaise(PasswordMismatchException())
        }
    }
    .handleErrorWith(
        predicate = { e -> e is UnsupportedMasterKdfVersionException },
    ) {
        ioRaise(PasswordMismatchException())
    }
