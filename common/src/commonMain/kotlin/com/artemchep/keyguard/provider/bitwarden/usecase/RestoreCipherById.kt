package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.RestoreCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.deletedDate
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RestoreCipherByIdImpl(
    private val modifyCipherById: ModifyCipherById,
) : RestoreCipherById {
    companion object {
        private const val TAG = "RestoreCipherById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIds: Set<String>,
    ): IO<Unit> = performRestoreCipher(
        cipherIds = cipherIds,
    ).map { Unit }

    private fun performRestoreCipher(
        cipherIds: Set<String>,
    ) = modifyCipherById(
        cipherIds,
    ) { model ->
        var new = model
        // Un-trash the model.
        new = model.copy(
            data_ = BitwardenCipher.deletedDate.set(new.data_, null),
        )
        new
    }
}
