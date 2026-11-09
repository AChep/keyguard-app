package com.artemchep.keyguard.provider.keyguard.applink

import app.cash.sqldelight.coroutines.asFlow
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentBundle
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.provider.keyguard.P2pGroupBlobs
import com.artemchep.keyguard.provider.keyguard.P2pGroupHost
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Linked apps as the shared sync machinery sees them; an app has at most one link,
 * [P2pGroupId.AppLink]. Their entries are recorded when the records change, so there are
 * never local edits a pass has to commit; the envelope holds the entries of the categories
 * this device syncs, see [AppSyncSwitches].
 *
 * @param queue queues a sync pass of the link, see [AppLinkSync].
 */
internal class AppLinkHost(
    private val db: VaultDatabaseManager,
    private val categories: AppSyncCategories,
    private val switches: AppSyncSwitches,
    private val service: AppLinkService,
    private val blobs: AppLinkBlobs,
    private val queue: () -> IO<Unit>,
) : P2pGroupHost {
    override val scope: SyncScope get() = SyncScope.APP

    override suspend fun buildLocalState(groupId: P2pGroupId): List<SyncEntry> {
        val enabled = switches.enabledNow()
        val database = db.get().bind()
        return KeyguardSyncStore(database, groupId.id)
            .loadEntries()
            .values
            .filter { entry -> categories.syncs(entry, enabled) }
            .sortedBy { it.id }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun localChanges(groupId: P2pGroupId): Flow<Unit> = merge(
        db.get()
            .asFlow()
            .flatMapLatest { database ->
                database.p2pSyncEntryQueries
                    .getByGroupId(groupId = groupId.id)
                    .asFlow()
            }
            .map { },
        switches.enabled().map { },
    )

    override fun queueSync(groupId: P2pGroupId): IO<Unit> = queue()

    override fun blobs(groupId: P2pGroupId): P2pGroupBlobs = blobs

    override suspend fun checkJoinable() = service.checkJoinable()

    override suspend fun import(
        bundle: EnrollmentBundle,
        device: P2pDevice,
        network: P2pNetwork,
    ): P2pGroupId {
        service.import(bundle, device, network)
        return P2pGroupId.AppLink
    }

    override suspend fun dissolve(groupId: P2pGroupId) = service.dissolve()
}
