package com.artemchep.keyguard.provider.keyguard

import app.cash.sqldelight.Query
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.keyguard.applink.appLinkMembershipOrNull
import com.artemchep.keyguard.provider.keyguard.applink.saveAppLinkMembership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class P2pMembershipStoreTest {
    private val db: Database = createUploadTestDatabase()

    private val store = P2pMembershipStore(UploadTestVaultDatabaseManager(db))

    @Test
    fun `the membership of linked apps is read back`() = runTest {
        val membership = testP2pMembership()
        db.saveAppLinkMembership(membership)

        assertEquals(membership, store.get(P2pGroupId.AppLink))
        assertEquals(membership, db.appLinkMembershipOrNull())
    }

    @Test
    fun `a group that is not there has no membership`() = runTest {
        assertNull(store.get(P2pGroupId.AppLink))
        assertNull(store.get(P2pGroupId("missing")))
    }

    @Test
    fun `a membership that can not be read is none`() = runTest {
        db.p2pGroupQueries.insert(
            groupId = P2pGroupId.APP_LINK_ID,
            scope = "app",
            membership = "{",
        )

        assertNull(store.get(P2pGroupId.AppLink))
    }

    @Test
    fun `the membership is observed as it changes`() = runTest {
        val membership = testP2pMembership()
        val observed = Channel<P2pMembership?>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) {
            store.observe(P2pGroupId.AppLink).collect { observed.send(it) }
        }
        assertNull(observed.receive())

        db.saveAppLinkMembership(membership)
        assertEquals(membership, observed.receive())

        val renamed = store.update(P2pGroupId.AppLink) { data ->
            data.copy(device = data.device.copy(label = "Desktop"))
        }
        assertEquals(renamed, observed.receive())

        db.p2pGroupQueries.deleteByGroupId(groupId = P2pGroupId.APP_LINK_ID)
        assertNull(observed.receive())
        job.cancel()
    }

    @Test
    fun `an update writes into the group key, founder, device and network`() = runTest {
        val membership = testP2pMembership()
        db.saveAppLinkMembership(membership)
        val network = P2pNetwork(relayUrl = "wss://relay.example.com", allowLan = false)

        val updated = store.update(P2pGroupId.AppLink) { data ->
            data.copy(
                groupKeyBase64 = "new-group-key",
                founderId = "new-device",
                device = data.device.copy(deviceId = "new-device", label = "Desktop"),
                network = network,
            )
        }

        val expected = P2pMembership(
            groupId = membership.groupId,
            groupKeyBase64 = "new-group-key",
            founderId = "new-device",
            device = membership.device.copy(deviceId = "new-device", label = "Desktop"),
            network = network,
        )
        assertEquals(expected, updated)
        assertEquals(expected, store.get(P2pGroupId.AppLink))
    }

    @Test
    fun `an update that changes nothing writes nothing`() = runTest {
        val membership = testP2pMembership()
        db.saveAppLinkMembership(membership)
        var writes = 0
        val query = db.p2pGroupQueries.getByGroupId(groupId = P2pGroupId.APP_LINK_ID)
        val listener = Query.Listener { writes += 1 }
        query.addListener(listener)

        val updated = store.update(P2pGroupId.AppLink) { it }

        query.removeListener(listener)
        assertEquals(membership, updated)
        assertEquals(0, writes)
    }

    @Test
    fun `a group keeps the wire name of its scope`() = runTest {
        db.saveAppLinkMembership(testP2pMembership())
        assertEquals("app", db.p2pGroupQueries.getByGroupId(P2pGroupId.APP_LINK_ID).executeAsOne().scope)
        assertEquals(SyncScope.APP, db.p2pGroupScopeOrNull(P2pGroupId.AppLink))

        // A scope of a newer build, and a group that is not there.
        db.p2pGroupQueries.insert(groupId = "newer", scope = "unknown", membership = "{}")
        assertNull(db.p2pGroupScopeOrNull(P2pGroupId("newer")))
        assertNull(db.p2pGroupScopeOrNull(P2pGroupId("missing")))
    }

    @Test
    fun `a group that is not there can not be updated`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            store.update(P2pGroupId.AppLink) { it }
        }
        assertFailsWith<IllegalArgumentException> {
            store.update(P2pGroupId("missing")) { it }
        }
        assertNull(store.get(P2pGroupId.AppLink))
    }
}
