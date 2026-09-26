package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.CipherHistoryType
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory

class AddCipherUsedPasskeyHistoryImpl(
    private val db: VaultDatabaseManager,
) : AddCipherUsedPasskeyHistory {
    companion object {
        private const val TAG = "AddCipherUsedPasskey"
    }

    override fun invoke(request: AddCipherUsedPasskeyHistoryRequest) = db
        .mutate(TAG) {
            it.cipherUsageHistoryQueries.insert(
                cipherId = request.cipherId,
                credentialId = request.credentialId,
                type = CipherHistoryType.USED_PASSKEY,
                createdAt = request.instant,
            )
            Unit
        }
}
