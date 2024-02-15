package com.artemchep.keyguard.provider.bitwarden.usecase.util

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.data.bitwarden.Profile
import kotlinx.datetime.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class ModifyProfileById(
    private val modifyDatabase: ModifyDatabase,
) {
    companion object {
        private const val TAG = "ModifyProfileById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyDatabase = directDI.instance(),
    )

    operator fun invoke(
        profileIds: Set<String>,
        checkIfChanged: Boolean = true,
        transform: suspend (Profile) -> Profile,
    ): IO<Unit> = modifyDatabase { database ->
        val dao = database.profileQueries
        val now = Clock.System.now()
        val models = dao
            .get()
            .executeAsList()
            .filter {
                it.profileId in profileIds
            }
            .mapNotNull { model ->
                var new = model
                new = transform(new)
                // If the profile was not changed, then we do not need to
                // update it in the database.
                if (checkIfChanged && new == model) {
                    return@mapNotNull null
                }

                new
            }
        if (models.isEmpty()) {
            return@modifyDatabase ModifyDatabase.Result.unit()
        }
        dao.transaction {
            models.forEach { profile ->
                dao.insert(
                    profileId = profile.profileId,
                    accountId = profile.accountId,
                    data = profile.data_,
                )
            }
        }

        val changedAccountIds = models
            .map { AccountId(it.accountId) }
            .toSet()
        ModifyDatabase.Result.unit(changedAccountIds)
    }
}
