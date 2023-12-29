package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AddPasskeyCipherRequest
import com.artemchep.keyguard.common.usecase.AddPasskeyCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import kotlinx.datetime.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class AddPasskeyCipherImpl(
    private val modifyCipherById: ModifyCipherById,
) : AddPasskeyCipher {
    companion object {
        private const val TAG = "AddPasskeyCipher.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        request: AddPasskeyCipherRequest,
    ): IO<Boolean> = ioEffect {
        val createdAt = Clock.System.now()
        modifyCipherById(
            setOf(request.cipherId),
        ) { model ->
            var new = model

            val oldUris = model.data_.login?.fido2Credentials.orEmpty()
            val newUris = kotlin.run {
                val passkey = BitwardenCipher.Login.Fido2Credentials(
                    credentialId = request.data.credentialId,
                    keyType = request.data.keyType,
                    keyAlgorithm = request.data.keyAlgorithm,
                    keyCurve = request.data.keyCurve,
                    keyValue = request.data.keyValue,
                    rpId = request.data.rpId,
                    rpName = request.data.rpName,
                    counter = request.data.counter?.toString().orEmpty(),
                    userHandle = request.data.userHandle,
                    userName = request.data.userName,
                    userDisplayName = request.data.userDisplayName,
                    discoverable = request.data.discoverable.toString(),
                    creationDate = createdAt,
                )
                listOf(passkey)
            }
            if (newUris.isEmpty()) {
                return@modifyCipherById new
            }
            new = new.copy(
                data_ = new.data_.copy(
                    login = new.data_.login?.copy(
                        fido2Credentials = oldUris + newUris,
                    ),
                ),
            )
            new
        }
            // Report that we have actually modified the
            // ciphers.
            .map { changedCipherIds ->
                request.cipherId in changedCipherIds
            }
    }
        .flatten()
}
