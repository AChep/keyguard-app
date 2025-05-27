package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.AddCipherOpenedHistoryRequest
import com.artemchep.keyguard.common.model.CipherHistoryType
import com.artemchep.keyguard.common.usecase.AddCipherUsedAutofillHistory
import com.artemchep.keyguard.core.store.DatabaseManager
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddCipherUsedAutofillHistoryImpl(
    private val db: DatabaseManager,
) : AddCipherUsedAutofillHistory {
    companion object {
        private const val TAG = "AddCipherUsedAutofill"
    }

    constructor(directDI: DirectDI) : this(
        db = directDI.instance(),
    )

    override fun invoke(request: AddCipherOpenedHistoryRequest) = db
        .mutate(TAG) {
            it.cipherUsageHistoryQueries.insert(
                cipherId = request.cipherId,
                credentialId = null,
                type = CipherHistoryType.USED_AUTOFILL,
                createdAt = request.instant,
            )
            Unit
        }
}
