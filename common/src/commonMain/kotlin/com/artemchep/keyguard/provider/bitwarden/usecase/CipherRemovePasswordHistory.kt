package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.CipherRemovePasswordHistory
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.passwordHistory
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById

/**
 * @author Artem Chepurnyi
 */
class CipherRemovePasswordHistoryImpl(
    private val modifyCipherById: ModifyCipherById,
) : CipherRemovePasswordHistory {
    companion object {
        private const val TAG = "CipherRemovePasswordHistory.bitwarden"
    }

    override fun invoke(
        cipherId: String,
    ): IO<Unit> = modifyCipherById(
        setOf(cipherId),
    ) { model ->
        var new = model
        new = new.copy(
            data_ = BitwardenCipher.passwordHistory
                .set(new.data_, emptyList()),
        )
        new
    }.map { Unit }
}
