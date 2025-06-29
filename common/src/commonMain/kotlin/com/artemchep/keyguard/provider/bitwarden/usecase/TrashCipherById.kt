package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.deletedDate
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class TrashCipherByIdImpl(
    private val modifyCipherById: ModifyCipherById,
) : TrashCipherById {
    companion object {
        private const val TAG = "TrashCipherById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIds: Set<String>,
    ): IO<Unit> = performTrashCipher(
        cipherIds = cipherIds,
    ).map { Unit }

    private fun performTrashCipher(
        cipherIds: Set<String>,
    ) = modifyCipherById(
        cipherIds,
        // Bitwarden does not update revision date of a
        // trashed cipher.
        updateRevisionDate = false,
    ) { model ->
        var new = model
        // Add the deleted instant to mark the model as trashed.
        // This does not actually remove the cipher, it
        // only puts it in the bin.
        val now = Clock.System.now()
        new = new.copy(
            data_ = BitwardenCipher.deletedDate.set(new.data_, now),
        )
        new
    }
}
