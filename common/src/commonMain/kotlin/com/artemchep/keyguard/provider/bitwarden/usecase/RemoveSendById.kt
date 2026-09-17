package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.usecase.RemoveSendById
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifySendById

/**
 * @author Artem Chepurnyi
 */
class RemoveSendByIdImpl(
    private val modifySendById: ModifySendById,
    private val pendingUploadCoordinator: PendingUploadCoordinator,
) : RemoveSendById {
    companion object {
        private const val TAG = "RemoveSendById.bitwarden"
    }

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
        model.data_.file?.pendingUpload?.let { pendingUpload ->
            pendingUploadCoordinator.delete(pendingUpload)
        }

        var new = model
        new = new.copy(
            data_ = new.data_.copy(
                service = new.data_.service.copy(
                    deleted = true,
                ),
                file = new.data_.file?.copy(
                    pendingUpload = null,
                ),
            ),
        )
        new
    }
}
