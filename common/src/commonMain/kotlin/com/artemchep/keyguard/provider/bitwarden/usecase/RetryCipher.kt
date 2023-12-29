package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.RetryCipher
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RetryCipherImpl(
    private val modifyCipherById: ModifyCipherById,
) : RetryCipher {
    companion object {
        private const val TAG = "RetryCipher.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIds: Set<String>,
    ): IO<Unit> = modifyCipherById(
        cipherIds = cipherIds,
        checkIfChanged = false,
    ) { model ->
        model // change nothing
    }.map { Unit }
}
