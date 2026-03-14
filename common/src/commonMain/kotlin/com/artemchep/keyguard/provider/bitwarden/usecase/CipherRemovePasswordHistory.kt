package com.artemchep.keyguard.provider.bitwarden.usecase

import arrow.optics.dsl.notNull
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.CipherRemovePasswordHistory
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.login
import com.artemchep.keyguard.core.store.bitwarden.passwordHistory
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CipherRemovePasswordHistoryImpl(
    private val modifyCipherById: ModifyCipherById,
) : CipherRemovePasswordHistory {
    companion object {
        private const val TAG = "CipherRemovePasswordHistory.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherId: String,
    ): IO<Unit> = modifyCipherById(
        setOf(cipherId),
    ) { model ->
        var new = model
        new = new.copy(
            data_ = BitwardenCipher.login.notNull.passwordHistory
                .set(new.data_, emptyList()),
        )
        new
    }.map { Unit }
}
