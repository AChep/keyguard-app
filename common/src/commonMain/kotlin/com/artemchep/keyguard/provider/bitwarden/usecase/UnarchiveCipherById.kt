package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.UnarchiveCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.archivedDate
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class UnarchiveCipherByIdImpl(
    private val modifyCipherById: ModifyCipherById,
) : UnarchiveCipherById {
    companion object {
        private const val TAG = "UnarchiveCipherById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIds: Set<String>,
    ): IO<Unit> = performUnarchiveCipher(
        cipherIds = cipherIds,
    ).map { Unit }

    private fun performUnarchiveCipher(
        cipherIds: Set<String>,
    ) = modifyCipherById(
        cipherIds,
    ) { model ->
        var new = model
        // Un-archive the model.
        new = model.copy(
            data_ = BitwardenCipher.archivedDate.set(new.data_, null),
        )
        new
    }
}
