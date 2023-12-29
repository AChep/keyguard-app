package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.RemoveCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.deleted
import com.artemchep.keyguard.core.store.bitwarden.service
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveCipherByIdImpl(
    private val modifyCipherById: ModifyCipherById,
) : RemoveCipherById {
    companion object {
        private const val TAG = "RemoveCipherById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIds: Set<String>,
    ): IO<Unit> = performRemoveCipher(
        cipherIds = cipherIds,
    ).map { Unit }

    private fun performRemoveCipher(
        cipherIds: Set<String>,
    ) = modifyCipherById(
        cipherIds = cipherIds,
        checkIfStub = false, // we want to be able to delete failed items
    ) { model ->
        var new = model
        new = new.copy(
            data_ = BitwardenCipher.service.deleted.set(new.data_, true),
        )
        new
    }
}
