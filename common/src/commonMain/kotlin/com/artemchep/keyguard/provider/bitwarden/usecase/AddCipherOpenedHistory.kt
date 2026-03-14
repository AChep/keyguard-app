package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.AddCipherOpenedHistoryRequest
import com.artemchep.keyguard.common.model.CipherHistoryType
import com.artemchep.keyguard.common.usecase.AddCipherOpenedHistory
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddCipherOpenedHistoryImpl(
    private val db: VaultDatabaseManager,
) : AddCipherOpenedHistory {
    companion object {
        private const val TAG = "AddCipherOpened"
    }

    constructor(directDI: DirectDI) : this(
        db = directDI.instance(),
    )

    override fun invoke(request: AddCipherOpenedHistoryRequest) = db
        .mutate(TAG) {
            it.cipherUsageHistoryQueries.insert(
                cipherId = request.cipherId,
                credentialId = null,
                type = CipherHistoryType.OPENED,
                createdAt = request.instant,
            )
            Unit
        }
}
