package com.artemchep.keyguard.common.service.appsync

import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.model.EntryKind

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
