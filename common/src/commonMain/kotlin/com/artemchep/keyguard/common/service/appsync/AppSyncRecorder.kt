package com.artemchep.keyguard.common.service.appsync

import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlin.uuid.Uuid

/**
 * Records changes of the app's own records, such as the email forwarders, for linked apps.
 *
 * A repository runs its change through it: the change and its record share one transaction,
 * so a change is stamped at the time it was made. Without linked apps, nothing is recorded.
 */
fun interface AppSyncRecorder {
    /**
     * Runs the [mutation] of the records [ids] of the [kind] in a transaction of the
     * [database], joining the one that runs, and records what it changed of them: a record
     * that is gone was deleted. The [ids] are read in that transaction, before the [mutation].
     */
    fun record(
        database: Database,
        kind: EntryKind,
        ids: () -> Collection<String>,
        mutation: () -> Unit,
    )

    companion object {
        /** Records nothing. */
        val None: AppSyncRecorder = AppSyncRecorder { database, _, _, mutation ->
            database.transaction { mutation() }
        }
    }
}

/**
 * Records the write of one record of the [kind] a repository puts: an update of the row
 * its model names, or an insert of a new one, which gets a new sync id. The row is read,
 * written and recorded in one transaction of the [database].
 *
 * @param find returns the row the model names, or `null` to add a new one.
 * @param syncIdOf returns the sync id of a row; a row without one is never recorded.
 * @return the sync id of the record it added, or `null` if it updated one.
 */
fun <Row : Any> AppSyncRecorder.recordPut(
    database: Database,
    kind: EntryKind,
    find: () -> Row?,
    syncIdOf: (Row) -> String?,
    update: (Row) -> Unit,
    insert: (syncId: String) -> Unit,
): String? = database.transactionWithResult {
    val existing = find()
    if (existing != null) {
        record(database, kind, ids = { listOfNotNull(syncIdOf(existing)) }) { update(existing) }
        null
    } else {
        val syncId = Uuid.random().toString()
        record(database, kind, ids = { listOf(syncId) }) { insert(syncId) }
        syncId
    }
}
