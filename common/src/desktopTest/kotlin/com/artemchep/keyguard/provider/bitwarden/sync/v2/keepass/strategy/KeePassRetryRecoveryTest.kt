package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.strategy

import app.keemobile.kotpass.models.Group
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.buildEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncPlanBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Suppress("FunctionNaming")
class KeePassRetryRecoveryTest {
    @Test
    fun `cipher retry adopts an already published entry`() {
        val remote = remoteCipher(revisionDate = REVISION_DATE)
        val strategy = KeePassCipherSyncStrategy(
            remoteFolderIdToLocalId = { null },
            remoteItemsById = mapOf(remote.id to remote),
        )

        val plan = EntitySyncPlanBuilder(strategy).buildPlan(
            localEntities = listOf(testBitwardenCipher(cipherId = ITEM_ID)),
            serverEntities = listOf(remote),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = ITEM_ID,
                    serverId = ITEM_ID,
                ),
            ),
            plan.actions,
        )
    }

    @Test
    fun `cipher edited after interrupted write updates the published entry`() {
        val remote = remoteCipher(revisionDate = REVISION_DATE)
        val strategy = KeePassCipherSyncStrategy(
            remoteFolderIdToLocalId = { null },
            remoteItemsById = mapOf(remote.id to remote),
        )
        val local = testBitwardenCipher(cipherId = ITEM_ID).copy(
            revisionDate = LATER_REVISION_DATE,
        )

        val plan = EntitySyncPlanBuilder(strategy).buildPlan(
            localEntities = listOf(local),
            serverEntities = listOf(remote),
        )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = ITEM_ID,
                    serverId = ITEM_ID,
                ),
            ),
            plan.actions,
        )
    }

    @Test
    fun `folder retry adopts an already published group`() {
        val remote = KeePassFolder(
            group = Group(
                uuid = Uuid.parse(ITEM_ID),
                name = "Folder",
            ),
            name = "Folder",
            revisionDate = REVISION_DATE,
        )
        val strategy = KeePassFolderSyncStrategy(
            remoteFolderIdToLocalId = { null },
            remoteItemsById = mapOf(remote.id to remote),
        )

        val plan = EntitySyncPlanBuilder(strategy).buildPlan(
            localEntities = listOf(testBitwardenFolder(folderId = ITEM_ID)),
            serverEntities = listOf(remote),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = ITEM_ID,
                    serverId = ITEM_ID,
                ),
            ),
            plan.actions,
        )
    }

    private fun remoteCipher(revisionDate: Instant): KeePassCipher =
        KeePassCipher(
            group = Group(
                uuid = Uuid.parse(GROUP_ID),
                name = "Root",
            ),
            cipher = buildEntry(
                uuid = Uuid.parse(ITEM_ID),
                title = "Cipher",
            ),
            revisionDate = revisionDate,
        )
}

private const val ITEM_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private const val GROUP_ID = "94c40f7c-e6e1-4ec3-b3a0-eb8d54c19c5a"
private val REVISION_DATE = Instant.parse("2024-01-01T00:00:00Z")
private val LATER_REVISION_DATE = Instant.parse("2024-01-02T00:00:00Z")
