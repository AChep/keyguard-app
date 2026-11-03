package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.isCustom

class AccountCertificateConflictException : IllegalStateException()
class AccountCertificateUnavailableException : IllegalStateException()

class PutAccountMutualTls(
    private val db: VaultDatabaseManager,
    private val markBackupAsDirty: MarkBackupAsDirty,
    private val queueSyncById: QueueSyncById,
    private val windowCoroutineScope: WindowCoroutineScope,
) {
    operator fun invoke(
        accountId: AccountId,
        expected: ServerEnv.MutualTls?,
        replacement: ServerEnv.MutualTls?,
    ): IO<Unit> = ioEffect {
        db.mutate("PutAccountMutualTls") { database ->
            val current = database.accountQueries.getByAccountId(accountId.id)
                .executeAsOneOrNull()?.data_ as? BitwardenToken
                ?: throw AccountCertificateUnavailableException()
            if (!current.env.back().isCustom()) throw AccountCertificateUnavailableException()
            if (current.env.mutualTls != expected) throw AccountCertificateConflictException()
            database.accountQueries.insert(
                accountId = current.id,
                data = current.copy(env = current.env.copy(mutualTls = replacement)),
            )
        }.bind()
        // A failed network request must not roll back a locally saved certificate.
        markBackupAsDirty().bind()
        queueSyncById(accountId).launchIn(windowCoroutineScope)
    }
}
