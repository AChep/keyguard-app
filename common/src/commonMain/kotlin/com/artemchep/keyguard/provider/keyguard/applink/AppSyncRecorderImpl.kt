package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.appsync.AppSyncRecorder
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.merge.EditRefusedException
import com.artemchep.keyguard.feature.p2psync.merge.EntryBudget
import com.artemchep.keyguard.feature.p2psync.merge.EntrySigner
import com.artemchep.keyguard.feature.p2psync.merge.LocalEditPublisher
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Stamps a change of the app's own records the moment it is made, in the transaction that
 * makes it: the entry gets the new body, with a write signed by this device for each part
 * that changed, or a tombstone. So an edit made while a category does not sync, or while offline, keeps its
 * time, and wins or loses by it once the devices merge.
 *
 * A part changed if the mutation changed it in the row. The others keep what the entry
 * holds, also where the row is behind it, such as a wordlist whose new words are not here
 * yet: an edit never brings back what the row still shows of an older version.
 *
 * A change is not recorded if an active device does not support its kind, see
 * [com.artemchep.keyguard.feature.p2psync.roster.GroupCaps], or if its version breaks a
 * check the peers run, see [EditRefusedException]: the entry stays as it was, the local
 * row keeps the change, and the [logRepository] hears why.
 *
 * Without linked apps nothing is recorded: linking publishes the records then, see
 * [AppLinkSyncPass.publishUnrecorded].
 */
internal class AppSyncRecorderImpl(
    private val categories: AppSyncCategories,
    private val crypto: P2pCrypto,
    private val logRepository: LogRepository? = null,
    private val now: () -> Instant = { Clock.System.now() },
) : AppSyncRecorder {
    companion object {
        private const val TAG = "AppSyncRecorder"
    }

    private val publisher = LocalEditPublisher()

    override fun record(
        database: Database,
        kind: EntryKind,
        ids: () -> Collection<String>,
        mutation: () -> Unit,
    ) {
        database.transaction {
            recordInTransaction(database, kind, ids(), mutation)
        }
    }

    private fun recordInTransaction(
        database: Database,
        kind: EntryKind,
        ids: Collection<String>,
        mutation: () -> Unit,
    ) {
        val membership = database.appLinkMembershipOrNull()
        val category = categories.forKind(kind)
        if (ids.isEmpty() || membership == null || category == null) {
            mutation()
            return
        }
        val before = ids.toSet().associateWith { id -> category.read(database, id) }
        mutation()
        val store = KeyguardSyncStore(database, P2pGroupId.APP_LINK_ID)
        if (!store.loadValidated(membership, crypto).caps.supports(kind)) {
            log("Not recording ${kind.wireName}: an active device does not support it")
            return
        }
        val clock = store.clock(membership.device.deviceId, now)
        val signer = EntrySigner(crypto, membership.groupId, membership.device.signPrivateKey())
        val entries = store.loadEntries()
        val budget = EntryBudget.of(entries.values)
        before.forEach { (id, old) ->
            val current = entries[EntryKey(kind, id)]
            val local = category.read(database, id)
            // A record that comes back after a delete is new again.
            val body = current?.body?.takeUnless { current.deleted }
            val next = try {
                when {
                    local != null -> publisher.publish(
                        id = id,
                        kind = kind,
                        current = current,
                        // The row before the mutation: the edit is what the mutation changed.
                        applied = if (body != null && old != null) JsonObject(body + old) else null,
                        // Keep what a newer build added to the body.
                        local = JsonObject(current?.body.orEmpty() + local),
                        clock = clock,
                        signer = signer,
                        budget = budget,
                    )

                    current != null -> publisher.tombstone(current, clock, signer, budget)
                    else -> null
                }
            } catch (e: EditRefusedException) {
                // The peers would drop it: the change stays on this device.
                log("Not recording ${kind.wireName} $id: ${e.reason}")
                null
            }
            if (next != null && next != current) {
                store.saveEntry(next)
            }
        }
    }

    private fun log(message: String) {
        logRepository?.post(TAG, message, LogLevel.WARNING)
    }
}
