package com.artemchep.keyguard.provider.bitwarden.usecase.util

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.data.bitwarden.Send
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class ModifySendById(
    private val modifyDatabase: ModifyDatabase,
) {
    companion object {
        private const val TAG = "ModifySendById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyDatabase = directDI.instance(),
    )

    operator fun invoke(
        sendIds: Set<String>,
        checkIfStub: Boolean = true,
        checkIfChanged: Boolean = true,
        updateRevisionDate: Boolean = true,
        transform: suspend (Send) -> Send,
    ): IO<Set<String>> = modifyDatabase { database ->
        val dao = database.sendQueries
        val now = Clock.System.now()
        val models = dao
            .get()
            .executeAsList()
            .filter {
                it.sendId in sendIds
            }
            .mapNotNull { model ->
                // If the send was not properly decoded, then
                // prevent it from being pushed to backend.
                val service = model.data_.service
                if (checkIfStub && !service.canEdit()) {
                    return@mapNotNull null
                }
                var new = model
                new = transform(new)
                // If the cipher was not changed, then we do not need to
                // update it in the database.
                if (checkIfChanged && new == model) {
                    return@mapNotNull null
                }

                if (updateRevisionDate) {
                    new = new.copy(
                        data_ = new.data_.copy(
                            revisionDate = now,
                        ),
                    )
                }
                new
            }
        if (models.isEmpty()) {
            return@modifyDatabase ModifyDatabase.Result(
                changedAccountIds = emptySet(),
                value = emptySet(),
            )
        }
        dao.transaction {
            models.forEach { send ->
                dao.insert(
                    sendId = send.sendId,
                    accountId = send.accountId,
                    data = send.data_,
                )
            }
        }

        val changedAccountIds = models
            .map { AccountId(it.accountId) }
            .toSet()
        val changedSendIds = models
            .map { it.sendId }
            .toSet()
        ModifyDatabase.Result(
            changedAccountIds = changedAccountIds,
            value = changedSendIds,
        )
    }
}
