package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.ChangeCipherNameById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.name
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById

/**
 * @author Artem Chepurnyi
 */
class ChangeCipherNameByIdImpl(
    private val modifyCipherById: ModifyCipherById,
) : ChangeCipherNameById {
    companion object {
        private const val TAG = "ChangeCipherNameById.bitwarden"
    }

    override fun invoke(
        cipherIdsToNames: Map<String, String>,
    ): IO<Unit> = modifyCipherById(
        cipherIdsToNames
            .keys,
    ) { model ->
        val name = cipherIdsToNames.getValue(model.cipherId)
        var new = model
        new = new.copy(
            data_ = BitwardenCipher.name.set(new.data_, name),
        )
        new
    }.map { Unit }
}
