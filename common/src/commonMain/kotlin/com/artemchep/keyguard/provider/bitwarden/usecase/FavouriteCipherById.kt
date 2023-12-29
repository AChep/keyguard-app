package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.FavouriteCipherById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.favorite
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class FavouriteCipherByIdImpl(
    private val modifyCipherById: ModifyCipherById,
) : FavouriteCipherById {
    companion object {
        private const val TAG = "FavouriteCipherById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        cipherIds: Set<String>,
        favourite: Boolean,
    ): IO<Unit> = modifyCipherById(
        cipherIds,
    ) { model ->
        var new = model
        new = new.copy(
            data_ = BitwardenCipher.favorite.set(new.data_, favourite),
        )
        new
    }.map { Unit }
}
