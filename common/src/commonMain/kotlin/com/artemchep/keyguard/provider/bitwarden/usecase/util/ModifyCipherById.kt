package com.artemchep.keyguard.provider.bitwarden.usecase.util

import arrow.optics.dsl.notNull
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.login
import com.artemchep.keyguard.data.bitwarden.Cipher
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class ModifyCipherById(
    private val modifyDatabase: ModifyDatabase,
) {
    companion object {
        private const val TAG = "ModifyCipherById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyDatabase = directDI.instance(),
    )

    operator fun invoke(
        cipherIds: Set<String>,
        checkIfStub: Boolean = true,
        checkIfChanged: Boolean = true,
        updateRevisionDate: Boolean = true,
        transform: suspend (Cipher) -> Cipher,
    ): IO<Set<String>> = modifyDatabase { database ->
        val dao = database.cipherQueries
        val now = Clock.System.now()
        val models = dao
            .get()
            .executeAsList()
            .filter {
                it.cipherId in cipherIds
            }
            .mapNotNull { model ->
                // If the cipher was not properly decoded, then
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
                    // Password revision date:
                    // When we edit or create an item with a password, we must set the
                    // passwords revision date. Otherwise you loose the info of when the
                    // password was created.
                    val newData = BitwardenCipher.login.notNull.modify(new.data_) { login ->
                        val passwordRevisionDate = login.passwordRevisionDate
                            ?: new.data_.revisionDate
                                .takeIf {
                                    login.password != null ||
                                            login.passwordHistory.isNotEmpty()
                                }
                        login.copy(passwordRevisionDate = passwordRevisionDate)
                    }

                    new = new.copy(
                        data_ = newData.copy(
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
            models.forEach { cipher ->
                dao.insert(
                    cipherId = cipher.cipherId,
                    accountId = cipher.accountId,
                    folderId = cipher.folderId,
                    data = cipher.data_,
                    updatedAt = cipher.data_.revisionDate,
                )
            }
        }

        val changedAccountIds = models
            .map { AccountId(it.accountId) }
            .toSet()
        val changedCipherIds = models
            .map { it.cipherId }
            .toSet()
        ModifyDatabase.Result(
            changedAccountIds = changedAccountIds,
            value = changedCipherIds,
        )
    }
}
