package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.RemoveSendById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.deleted
import com.artemchep.keyguard.core.store.bitwarden.service
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifySendById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveSendByIdImpl(
    private val modifySendById: ModifySendById,
) : RemoveSendById {
    companion object {
        private const val TAG = "RemoveSendById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifySendById = directDI.instance(),
    )

    override fun invoke(
        sendIds: Set<String>,
    ): IO<Unit> = performRemoveSend(
        sendIds = sendIds,
    ).map { Unit }

    private fun performRemoveSend(
        sendIds: Set<String>,
    ) = modifySendById(
        sendIds = sendIds,
        checkIfStub = false, // we want to be able to delete failed items
    ) { model ->
        var new = model
        new = new.copy(
            data_ = BitwardenSend.service.deleted.set(new.data_, true),
        )
        new
    }
}
