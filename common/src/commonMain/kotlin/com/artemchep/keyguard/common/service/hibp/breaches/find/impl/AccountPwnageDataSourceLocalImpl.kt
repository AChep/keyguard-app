package com.artemchep.keyguard.common.service.hibp.breaches.find.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.hibp.breaches.find.AccountPwnageDataSourceLocal
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.pwnage.AccountBreach
import kotlinx.coroutines.CoroutineDispatcher
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class AccountPwnageDataSourceLocalImpl(
    private val databaseManager: DatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : AccountPwnageDataSourceLocal {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun put(
        entity: AccountBreach,
    ): IO<Unit> = dbEffect { db ->
        db.accountBreachQueries.insert(
            count = entity.count,
            updatedAt = entity.updatedAt,
            data = entity.data_,
            username = entity.username,
        )
    }

    override fun getOne(
        username: String,
    ): IO<AccountBreach?> = dbEffect { db ->
        val model = db.accountBreachQueries
            .getByUsername(username)
            .executeAsOneOrNull()
        model
    }

    override fun getMany(
        usernames: Collection<String>,
    ): IO<Map<String, AccountBreach?>> = dbEffect { db ->
        val list = db.accountBreachQueries
            .getByUsernames(usernames)
            .executeAsList()
        usernames
            .asSequence()
            .map { username ->
                val entity = list.firstOrNull { it.username == username }
                username to entity
            }
            .toMap()
    }

    override fun clear(): IO<Unit> = dbEffect { db ->
        db.accountBreachQueries.deleteAll()
    }

    private fun <T> dbEffect(block: suspend (Database) -> T) = databaseManager
        .get()
        .effectMap(dispatcher, block)
}
