package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope

/** Fresh SQL reads also see commits made by the other Apple process. No UI enrichment. */
internal class AutofillVaultReader(private val di: Scope) {
    suspend fun accountNames(): Map<String, String> {
        val db = di.get<VaultDatabaseManager>().get().bind()
        return withContext(di.get<CoroutineDispatcher>(qualifier = named<DatabaseDispatcher>())) {
            db.profileQueries.get().executeAsList().associate { row ->
                val profile = row.data_
                row.accountId to listOf(profile.name, profile.email)
                    .filter { it.isNotBlank() }.distinct().joinToString(" · ")
            }
        }
    }

    suspend fun read(cipherId: String? = null): List<BitwardenCipher> {
        val db = di.get<VaultDatabaseManager>().get().bind()
        return withContext(di.get<CoroutineDispatcher>(qualifier = named<DatabaseDispatcher>())) {
            db.transactionWithResult {
                val hiddenAccounts = db.profileQueries.get().executeAsList()
                    .filter { it.data_.hidden }
                    .map { it.accountId }
                    .toSet()
                val rows = if (cipherId == null) {
                    db.cipherQueries.get().executeAsList()
                } else {
                    listOfNotNull(db.cipherQueries.getByCipherId(cipherId).executeAsOneOrNull())
                }
                rows.map { it.data_ }.filter {
                    it.isAutofillEligible(hiddenAccounts)
                }
            }
        }
    }
}

internal fun BitwardenCipher.isAutofillEligible(hiddenAccounts: Set<String>): Boolean =
    !service.deleted && deletedDate == null && accountId !in hiddenAccounts
