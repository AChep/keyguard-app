package com.artemchep.keyguard.common.service.hibp.passwords.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceLocal
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.pwnage.PasswordBreach
import kotlinx.coroutines.CoroutineDispatcher
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class PasswordPwnageDataSourceLocalImpl(
    private val databaseManager: VaultDatabaseManager,
    private val dispatcher: CoroutineDispatcher,
) : PasswordPwnageDataSourceLocal {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    override fun put(
        entity: PasswordBreach,
    ): IO<Unit> = dbEffect { db ->
        db.passwordBreachQueries.insert(
            password = entity.password,
            count = entity.count,
            updatedAt = entity.updatedAt,
        )
    }

    override fun getOne(
        password: String,
    ): IO<PasswordBreach?> = dbEffect { db ->
        val model = db.passwordBreachQueries
            .getByPassword(password)
            .executeAsOneOrNull()
        model
    }

    override fun getMany(
        passwords: Collection<String>,
    ): IO<Map<String, PasswordBreach?>> = dbEffect { db ->
        val list = db.passwordBreachQueries
            .getByPasswords(passwords)
            .executeAsList()
        passwords
            .asSequence()
            .map { password ->
                val entity = list.firstOrNull { it.password == password }
                password to entity
            }
            .toMap()
    }

    override fun clear(): IO<Unit> = dbEffect { db ->
        db.passwordBreachQueries.deleteAll()
    }

    private fun <T> dbEffect(block: suspend (Database) -> T) = databaseManager
        .get()
        .effectMap(dispatcher, block)
}
