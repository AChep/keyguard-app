package com.artemchep.keyguard.provider.bitwarden.usecase

import arrow.optics.dsl.notNull
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.ChangeCipherPasswordById
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.login
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withPasswordChange
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class ChangeCipherPasswordByIdImpl(
    private val modifyCipherById: ModifyCipherById,
    private val getPasswordStrength: GetPasswordStrength,
) : ChangeCipherPasswordById {
    companion object {
        private const val TAG = "ChangeCipherPasswordById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
        getPasswordStrength = directDI.instance(),
    )

    override fun invoke(
        cipherIdsToPasswords: Map<String, String>,
    ): IO<Unit> = ioUnit()
        .flatMap {
            val now = Clock.System.now()
            modifyCipherById(
                cipherIdsToPasswords
                    .keys,
            ) { model ->
                val password = cipherIdsToPasswords.getValue(model.cipherId)
                    // so we can clear password
                    .takeUnless { it.isEmpty() }
                val passwordStrength = password
                    ?.let { pwd ->
                        getPasswordStrength(pwd)
                            .map { ps ->
                                BitwardenCipher.Login.PasswordStrength(
                                    password = pwd,
                                    crackTimeSeconds = ps.crackTimeSeconds,
                                    version = ps.version,
                                )
                            }
                    }?.attempt()?.bind()?.getOrNull()

                var new = model
                val oldPasswordHistory = new.data_.passwordHistory
                var passwordHistory = oldPasswordHistory
                val data = BitwardenCipher.login.notNull.modify(new.data_) { login ->
                    passwordHistory = oldPasswordHistory.withPasswordChange(
                        previousPassword = login.password,
                        nextPassword = password,
                        at = now,
                    )

                    val passwordRevisionDate = now
                        .takeIf {
                            // The password must be v2+ to have the revision date.
                            password != login.password &&
                                    (login.passwordRevisionDate != null || login.password != null)
                        }
                        ?: login.passwordRevisionDate

                    login.copy(
                        password = password,
                        passwordRevisionDate = passwordRevisionDate,
                        passwordStrength = passwordStrength,
                    )
                }.copy(passwordHistory = passwordHistory)
                new = new.copy(
                    data_ = data,
                )
                new
            }
        }.map { Unit }
}
