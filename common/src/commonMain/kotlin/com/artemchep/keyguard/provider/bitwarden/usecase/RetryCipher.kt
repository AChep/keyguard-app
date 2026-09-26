package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.RetryCipher
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById

/**
 * @author Artem Chepurnyi
 */
class RetryCipherImpl(
    private val modifyCipherById: ModifyCipherById,
) : RetryCipher {
    companion object {
        private const val TAG = "RetryCipher.bitwarden"
    }

    override fun invoke(
        cipherIds: Set<String>,
    ): IO<Unit> = modifyCipherById(
        cipherIds = cipherIds,
        checkIfChanged = false,
    ) { model ->
        model // change nothing
    }.map { Unit }
}
