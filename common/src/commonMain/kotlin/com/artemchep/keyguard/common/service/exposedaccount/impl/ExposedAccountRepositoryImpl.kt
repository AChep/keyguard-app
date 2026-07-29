package com.artemchep.keyguard.common.service.exposedaccount.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccount
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountEntry
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRegistration
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRepository
import com.artemchep.keyguard.common.util.sqldelight.flatMapQueryToList
import com.artemchep.keyguard.dataexposed.Account as ExposedAccountEntity
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ExposedAccountRepositoryImpl(
    private val exposedDatabaseManager: ExposedDatabaseManager,
    private val cryptoGenerator: CryptoGenerator,
    private val dispatcher: CoroutineDispatcher,
) : ExposedAccountRepository {
    /**
     * Serialises the read-modify-write of the entry table.
     *
     * Minting is not idempotent, so two concurrent writers could otherwise both
     * generate an id for the same new account and register the loser's — leaving
     * the platform holding an id that is not the persisted one.
     */
    private val mutex = Mutex()

    constructor(
        directDI: DirectDI,
    ) : this(
        exposedDatabaseManager = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun get(): Flow<List<ExposedAccount>> = daoEffect { db ->
        db.accountQueries.get()
    }
        .flatMapQueryToList(dispatcher)
        .map { entities -> entities.map(::parseEntity) }

    override fun getRegistrations(): Flow<List<ExposedAccountRegistration>> = daoEffect { db ->
        val queries = db.credentialExchangeExportEntryQueries
        queries.getRegistrations { accountId, name, email, host, entryId ->
            val account = ExposedAccount(
                accountId = accountId,
                name = name,
                email = email,
                host = host,
            )
            ExposedAccountRegistration(
                accountId = accountId,
                entryId = entryId,
                label = account.label,
            )
        }
    }.flatMapQueryToList(dispatcher)

    override fun resolveEntry(
        entryId: String,
    ): IO<ExposedAccountEntry?> = daoEffect { db ->
        val entry = db.credentialExchangeExportEntryQueries
            .getByEntryId(entryId)
            .executeAsOneOrNull()
            ?: return@daoEffect null
        val account = db.accountQueries
            .getByAccountId(entry.accountId)
            .executeAsOneOrNull()
            ?.let(::parseEntity)
        ExposedAccountEntry(
            accountId = entry.accountId,
            account = account,
        )
    }

    override fun replaceAll(
        accounts: List<ExposedAccount>,
        allAccountIds: Set<String>,
    ): IO<Unit> = daoEffect { db ->
        mutex.withLock {
            db.transaction {
                // The account rows are a pure projection of the profiles, so
                // rewriting them wholesale is safe and matches the SSH/GPG mirrors.
                // The entry table is emphatically NOT rewritten — see
                // `mintMissingEntries`.
                db.accountQueries.deleteAll()
                accounts.forEach { account ->
                    db.accountQueries.insert(
                        accountId = account.accountId,
                        name = account.name,
                        email = account.email,
                        host = account.host,
                    )
                }
                mintMissingEntries(db, allAccountIds)
            }
        }
    }

    /**
     * Gives every id in [accountIds] an entry id if it has none.
     *
     * `INSERT OR IGNORE` is what preserves an existing id: rotating one silently
     * invalidates the registration the platform is holding, which surfaces only as
     * the account's picker row no longer working.
     */
    private fun mintMissingEntries(
        db: DatabaseExposed,
        accountIds: Set<String>,
    ) {
        accountIds.forEach { accountId ->
            db.credentialExchangeExportEntryQueries.insert(
                accountId = accountId,
                entryId = cryptoGenerator.uuid(),
            )
        }
    }

    private fun parseEntity(
        entity: ExposedAccountEntity,
    ): ExposedAccount = ExposedAccount(
        accountId = entity.accountId,
        name = entity.name,
        email = entity.email,
        host = entity.host,
    )

    private inline fun <T> daoEffect(
        crossinline block: suspend (DatabaseExposed) -> T,
    ): IO<T> = ioEffect(dispatcher) {
        val db = exposedDatabaseManager
            .get().bind()
        block(db)
    }
}
