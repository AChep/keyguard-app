package com.artemchep.keyguard.provider.bitwarden.usecase

import arrow.core.getOrElse
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.PatchSendRequest
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.PatchSendById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOptionalStringNullable
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifySendById
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class PatchSendByIdImpl(
    private val modifySendById: ModifySendById,
    private val base64Service: Base64Service,
) : PatchSendById {
    companion object {
        private const val TAG = "PatchSendById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifySendById = directDI.instance(),
        base64Service = directDI.instance(),
    )

    override fun invoke(
        patch: PatchSendRequest,
    ) = performPatchSend(
        patch = patch,
    ).map { true }

    private fun performPatchSend(
        patch: PatchSendRequest,
    ) = modifySendById(
        sendIds = patch.patch.keys,
    ) { model ->
        val p = patch.patch.getValue(model.sendId)

        val data = model.data_
            .run {
                val passwordBase64 = p.password
                    .map { newPassword ->
                        val newPasswordBase64 = newPassword
                            ?.let(base64Service::encodeToString)
                        newPasswordBase64.let(BitwardenOptionalStringNullable::Some)
                    }
                    .getOrElse { BitwardenOptionalStringNullable.None }
                copy(
                    name = p.name.getOrElse { name },
                    hideEmail = p.hideEmail.getOrElse { hideEmail },
                    disabled = p.disabled.getOrElse { disabled },
                    file = p.fileName
                        .map { fileName ->
                            file?.copy(
                                fileName = fileName,
                            )
                        }
                        .getOrElse { file },
                    changes = (changes ?: BitwardenSend.Changes()).copy(
                        passwordBase64 = passwordBase64,
                    ),
                )
            }
        model.copy(data_ = data)
    }
}
