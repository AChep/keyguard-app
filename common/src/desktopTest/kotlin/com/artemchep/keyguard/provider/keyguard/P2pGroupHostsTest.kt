package com.artemchep.keyguard.provider.keyguard

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentBundle
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class P2pGroupHostsTest {
    private val db: Database = createUploadTestDatabase()

    private val host = RecordingHost()

    private val hosts = P2pGroupHosts(
        db = UploadTestVaultDatabaseManager(db),
        hosts = listOf(host),
    )

    @Test
    fun `a group resolves to the host of the scope it is stored with`() = runTest {
        val groupId = P2pGroupId("group")
        db.saveP2pMembership(groupId, SyncScope.APP, testP2pMembership())

        assertSame(host, hosts.forGroup(groupId))
        assertSame(host, hosts.forScope(SyncScope.APP))
    }

    @Test
    fun `a group that is not there has no host`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            hosts.forGroup(P2pGroupId("missing"))
        }
    }

    @Test
    fun `a sync pass is queued through the host of the group`() = runTest {
        val groupId = P2pGroupId("group")
        db.saveP2pMembership(groupId, SyncScope.APP, testP2pMembership())

        hosts(groupId).bind()
        // A group that is gone, or of a scope this build does not know, has nothing to sync.
        hosts(P2pGroupId("missing")).bind()
        db.p2pGroupQueries.insert(groupId = "newer", scope = "unknown", membership = "{}")
        hosts(P2pGroupId("newer")).bind()

        assertEquals(listOf(groupId), host.queued)
    }

    @Test
    fun `each scope has one host`() {
        assertFailsWith<IllegalArgumentException> {
            P2pGroupHosts(
                db = UploadTestVaultDatabaseManager(db),
                hosts = listOf(RecordingHost(), RecordingHost()),
            )
        }
    }

    private class RecordingHost : P2pGroupHost {
        val queued = mutableListOf<P2pGroupId>()

        override val scope: SyncScope get() = SyncScope.APP

        override suspend fun buildLocalState(groupId: P2pGroupId) = emptyList<SyncEntry>()

        override fun localChanges(groupId: P2pGroupId): Flow<Unit> = emptyFlow()

        override fun queueSync(groupId: P2pGroupId): IO<Unit> = ioEffect {
            queued += groupId
            Unit
        }

        override suspend fun checkJoinable() = Unit

        override suspend fun import(
            bundle: EnrollmentBundle,
            device: P2pDevice,
            network: P2pNetwork,
        ): P2pGroupId = error("Not used.")

        override suspend fun dissolve(groupId: P2pGroupId) = Unit
    }
}
