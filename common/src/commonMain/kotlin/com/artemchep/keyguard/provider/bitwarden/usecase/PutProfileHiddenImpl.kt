package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.PutProfileHiddenRequest
import com.artemchep.keyguard.common.usecase.PutProfileHidden
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.hidden
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyProfileById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class PutProfileHiddenImpl(
    private val modifyProfileById: ModifyProfileById,
) : PutProfileHidden {
    constructor(directDI: DirectDI) : this(
        modifyProfileById = directDI.instance(),
    )

    override fun invoke(
        request: PutProfileHiddenRequest,
    ): IO<Boolean> = ioEffect {
        val profileIds = request.patch.keys
        modifyProfileById(
            profileIds,
        ) { model ->
            var new = model
            val hidden = request.patch[model.profileId]
                ?: return@modifyProfileById new
            new = new.copy(
                data_ = BitwardenProfile.hidden.set(new.data_, hidden),
            )
            new
        }
            // Report that we have actually modified the
            // profiles.
            .map { changedCipherIds ->
                true
            }
    }
        .flatten()
}
