package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.CipherRemovePasswordHistoryById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.login
import com.artemchep.keyguard.core.store.bitwarden.passwordHistory
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CipherRemovePasswordHistoryByIdImpl(
    private val modifyCipherById: ModifyCipherById,
) : CipherRemovePasswordHistoryById {
    companion object {
        private const val TAG = "CipherRemovePasswordHistoryById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherId: String,
        passwordIds: List<String>,
    ): IO<Unit> = modifyCipherById(
        setOf(cipherId),
    ) { model ->
        var new = model
        new = new.copy(
            data_ = BitwardenCipher.login.passwordHistory
                .modify(new.data_) { oldPasswordHistory ->
                    oldPasswordHistory
                        .filter { it.id !in passwordIds }
                },
        )
        new
    }.map { Unit }
}
