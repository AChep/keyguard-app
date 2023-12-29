package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.CipherHistoryType
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.core.store.DatabaseManager
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddCipherUsedPasskeyHistoryImpl(
    private val db: DatabaseManager,
) : AddCipherUsedPasskeyHistory {
    constructor(directDI: DirectDI) : this(
        db = directDI.instance(),
    )

    override fun invoke(request: AddCipherUsedPasskeyHistoryRequest) = db
        .mutate {
            it.cipherUsageHistoryQueries.insert(
                cipherId = request.cipherId,
                credentialId = request.credentialId,
                type = CipherHistoryType.USED_PASSKEY,
                createdAt = request.instant,
            )
        }
}
