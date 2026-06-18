package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty
import com.artemchep.keyguard.common.usecase.SyncById
import com.artemchep.keyguard.common.usecase.premium
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.feature.auth.companion.CompanionBitwardenPayload
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ImportCompanionBitwardenAccountImpl(
    private val getPurchased: GetPurchased,
    private val getAccounts: GetAccounts,
    private val syncById: SyncById,
    private val logRepository: LogRepository,
    private val db: VaultDatabaseManager,
    private val markBackupAsDirty: MarkBackupAsDirty,
) : ImportCompanionBitwardenAccount {
    companion object {
        private const val TAG = "AddAccount.bitwarden.companion"
    }

    constructor(directDI: DirectDI) : this(
        getPurchased = directDI.instance(),
        getAccounts = directDI.instance(),
        syncById = directDI.instance(),
        logRepository = directDI.instance(),
        db = directDI.instance(),
        markBackupAsDirty = directDI.instance(),
    )

    override fun invoke(
        payload: CompanionBitwardenPayload,
    ): IO<AccountId> = ioEffect(Dispatchers.Default) {
        val token = BitwardenToken(
            id = Uuid.random().toString(),
            key = BitwardenToken.Key(
                masterKeyBase64 = payload.masterKeyBase64,
                passwordKeyBase64 = payload.passwordKeyBase64,
                encryptionKeyBase64 = payload.encryptionKeyBase64,
                macKeyBase64 = payload.macKeyBase64,
            ),
            token = BitwardenToken.Token(
                refreshToken = payload.refreshToken,
                accessToken = payload.accessToken,
                expirationDate = Instant.parse(payload.accessTokenExpirationDate),
            ),
            user = BitwardenToken.User(
                email = payload.email,
            ),
            env = BitwardenToken.Environment.of(
                payload.env.toServerEnv(),
            ),
        )

        db.mutate(TAG) { database ->
            database.accountQueries.insert(
                accountId = token.id,
                data = token,
            )
        }.bind()
        markBackupAsDirty()
            .bind()

        AccountId(token.id)
    }
        .premium(
            getPurchased = getPurchased,
            predicate = AddAccountUtils.getPremiumAddAccountPredicateIo(
                getAccounts = getAccounts,
            ),
        )
        .flatTap { accountId ->
            syncById(accountId)
                .map { Unit }
        }
        .measure { duration, accountId ->
            logRepository.post(TAG, "Imported a companion Bitwarden account '$accountId' in $duration.")
        }
}
