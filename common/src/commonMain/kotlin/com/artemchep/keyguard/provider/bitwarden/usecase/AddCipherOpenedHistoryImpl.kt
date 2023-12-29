package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.AddCipherOpenedHistoryRequest
import com.artemchep.keyguard.common.model.CipherHistoryType
import com.artemchep.keyguard.common.usecase.AddCipherOpenedHistory
import com.artemchep.keyguard.core.store.DatabaseManager
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddCipherOpenedHistoryImpl(
    private val db: DatabaseManager,
) : AddCipherOpenedHistory {
    constructor(directDI: DirectDI) : this(
        db = directDI.instance(),
    )

    override fun invoke(request: AddCipherOpenedHistoryRequest) = db
        .mutate {
            it.cipherUsageHistoryQueries.insert(
                cipherId = request.cipherId,
                credentialId = null,
                type = CipherHistoryType.OPENED,
                createdAt = request.instant,
            )
        }
}
