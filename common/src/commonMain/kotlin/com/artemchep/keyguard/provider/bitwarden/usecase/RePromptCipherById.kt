package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.RePromptCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.reprompt
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RePromptCipherByIdImpl(
    private val modifyCipherById: ModifyCipherById,
) : RePromptCipherById {
    companion object {
        private const val TAG = "FavouriteCipherById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIds: Set<String>,
        reprompt: Boolean,
    ): IO<Unit> = modifyCipherById(
        cipherIds,
    ) { model ->
        val type = if (reprompt) {
            BitwardenCipher.RepromptType.Password
        } else {
            BitwardenCipher.RepromptType.None
        }

        var new = model
        new = new.copy(
            data_ = BitwardenCipher.reprompt.set(new.data_, type),
        )
        new
    }.map { Unit }
}
