package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.DeviceRecordRules
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionPublication
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.feature.p2psync.roster.identityHash
import com.artemchep.keyguard.provider.keyguard.session.openTestAuthority
import com.artemchep.keyguard.provider.keyguard.applink.selfSignedRecord
import com.artemchep.keyguard.provider.keyguard.applink.TEST_MASTER_PASSWORD
import com.artemchep.keyguard.provider.keyguard.P2pMembershipStore
import com.artemchep.keyguard.provider.keyguard.QueueP2pSync
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkTestDevice
import com.artemchep.keyguard.provider.keyguard.session.keyguardTransportOf
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// One test per behavior of the use cases.
@Suppress("TooManyFunctions")
class KeyguardDevicesUseCasesTest {
    @Test
    fun `lists the devices of the group with their fingerprints`() = runTest {
        val vault = testVault(this)
        val peer = vault.addPeer(label = "Phone")

        val devices = vault.getDevices(vault.groupId).first()

        assertEquals(2, devices.size)
        val self = devices.single { it.isThisDevice }
        assertEquals(vault.membership().device.deviceId, self.id)
        assertEquals("Laptop", self.label)
        assertFalse(self.revoked)
        val other = devices.single { !it.isThisDevice }
        assertEquals(peer.deviceId, other.id)
        assertEquals("Phone", other.label)
        assertFalse(other.revoked)
        devices.forEach { device ->
            assertTrue(FINGERPRINT.matches(device.fingerprint), device.fingerprint)
        }
        assertNotEquals(self.fingerprint, other.fingerprint)
    }

    @Test
    fun `an unknown group has no devices`() = runTest {
        val vault = testVault(this)

        val devices = vault.getDevices(P2pGroupId("not-a-group")).first()

        assertTrue(devices.isEmpty())
    }

    @Test
    fun `removes another device and queues a sync`() = runTest {
        val vault = testVault(this)
        val peer = vault.addPeer(label = "Phone")

        vault.revoke(vault.groupId, setOf(peer.deviceId), TEST_MASTER_PASSWORD).bind()
        advanceUntilIdle()

        val devices = vault.getDevices(vault.groupId).first()
        assertTrue(devices.single { it.id == peer.deviceId }.revoked)
        assertFalse(devices.single { it.isThisDevice }.revoked)
        // The revocation is signed by this device, so every device accepts it.
        val token = vault.membership()
        val stored = KeyguardSyncStore(vault.device.db, vault.groupId.id).loadRoster(token.founderId)
        val roster = vault.validator().validate(stored)
        assertEquals(listOf(peer.deviceId), roster.revoked.map { it.deviceId })
        assertEquals(setOf(token.device.deviceId), vault.activity(roster).activeIds)
        assertTrue(vault.queuedSyncs.contains(vault.groupId))
    }

    @Test
    fun `can not remove itself or an unknown device`() = runTest {
        val vault = testVault(this)
        val self = vault.membership().device.deviceId

        assertFailsWith<IllegalArgumentException> {
            vault.revoke(vault.groupId, setOf(self), TEST_MASTER_PASSWORD).bind()
        }
        assertFailsWith<IllegalArgumentException> {
            vault.revoke(vault.groupId, setOf("unknown"), TEST_MASTER_PASSWORD).bind()
        }
        assertTrue(vault.queuedSyncs.isEmpty())
    }

    @Test
    fun `removing a device needs the master password`() = runTest {
        val vault = testVault(this)
        val peer = vault.addPeer(label = "Phone")

        assertFailsWith<IllegalStateException> {
            vault.revoke(vault.groupId, setOf(peer.deviceId), "not the password").bind()
        }
        assertEquals(emptyList(), vault.roster().revoked)
        assertTrue(vault.queuedSyncs.isEmpty())
    }

    @Test
    fun `the devices a removed device added stay unless they are removed with it`() = runTest {
        val vault = testVault(this)
        val peer = vault.addPeer(label = "Phone")
        val kept = vault.addPeer(label = "Tablet", by = peer)
        val removed = vault.addPeer(label = "Stranger", by = peer)

        vault.revoke(vault.groupId, setOf(peer.deviceId, removed.deviceId), TEST_MASTER_PASSWORD).bind()

        val roster = vault.validator().validate(vault.roster())
        val activity = vault.activity(roster)
        assertTrue(activity.isActive(kept.deviceId))
        assertFalse(activity.isActive(peer.deviceId))
        assertFalse(activity.isActive(removed.deviceId))
        // The seal of the removed device leaves out the one removed with it.
        val seal = roster.revoked.single { it.deviceId == peer.deviceId }.admitted
        val keptRecord = roster.devices.single { it.id == kept.deviceId }
        assertEquals(listOf(keptRecord.identityHash(vault.device.crypto::sha256)), seal)
        val addedBy = vault.getDevices(vault.groupId).first().single { it.id == kept.deviceId }.addedBy
        assertEquals(peer.deviceId, addedBy)
    }

    @Test
    fun `renames this device in its signed record and queues a sync`() = runTest {
        val vault = testVault(this)
        val before = vault.membership()
        val original = vault.roster().devices.single()

        vault.putLabel(vault.groupId, "  Work laptop  ").bind()
        advanceUntilIdle()

        val after = vault.membership()
        assertEquals("Work laptop", after.device.label)
        assertEquals(before.device.copy(label = "Work laptop"), after.device)
        // The device signs the new name itself; the admission stays valid.
        val roster = vault.validator().validate(vault.roster())
        val record = roster.devices.single()
        assertEquals("Work laptop", record.label)
        assertTrue(record.updatedAt > original.updatedAt)
        assertEquals(original.admission, record.admission)
        assertTrue(vault.activity(roster).isActive(after.device.deviceId))
        val self = vault.getDevices(vault.groupId).first().single()
        assertEquals("Work laptop", self.label)
        assertEquals(listOf(vault.groupId), vault.queuedSyncs)

        // The same name changes nothing.
        vault.putLabel(vault.groupId, "Work laptop").bind()
        advanceUntilIdle()
        assertEquals(record, vault.roster().devices.single())
        assertEquals(listOf(vault.groupId), vault.queuedSyncs)

        assertFailsWith<IllegalArgumentException> {
            vault.putLabel(vault.groupId, "   ").bind()
        }
        // A name the other devices would drop is refused too.
        val invalid = listOf("line\nbreak", "rtl\u202Eoverride", "x".repeat(DeviceRecordRules.MAX_LABEL_LENGTH + 1))
        invalid.forEach { label ->
            assertFailsWith<IllegalArgumentException> {
                vault.putLabel(vault.groupId, label).bind()
            }
        }
        assertEquals(record, vault.roster().devices.single())
        // Up to the longest name, emoji included, it is taken.
        val longest = "😀".repeat(DeviceRecordRules.MAX_LABEL_LENGTH)
        vault.putLabel(vault.groupId, longest).bind()
        assertEquals(longest, vault.validator().validate(vault.roster()).devices.single().label)
    }

    @Test
    fun `a removed device renames itself on this device only`() = runTest {
        val vault = testVault(this)
        val peer = vault.addPeer(label = "Phone")
        val token = vault.membership()
        // The peer removes this device.
        val store = KeyguardSyncStore(vault.device.db, vault.groupId.id)
        val stored = store.loadRoster(token.founderId)
        val peerRecord = stored.devices.single { it.id == peer.deviceId }
        val revocation = openTestAuthority(peer).use { authority ->
            RosterSigner(vault.device.crypto, vault.membership().groupId).revoke(
                roster = stored,
                deviceId = token.device.deviceId,
                at = Hlc(wall = peerRecord.addedAt.wall + 1, counter = 0, node = peer.deviceId),
                by = peer.deviceId,
                authorityPrivateKey = authority.seed,
            )
        }
        store.saveRoster(stored.copy(revoked = stored.revoked + revocation))

        vault.putLabel(vault.groupId, "Work laptop").bind()
        advanceUntilIdle()

        assertEquals("Work laptop", vault.membership().device.label)
        assertEquals("Laptop", vault.roster().devices.single { it.id == token.device.deviceId }.label)
        assertTrue(vault.queuedSyncs.isEmpty())
    }

    @Test
    fun `saves the network settings`() = runTest {
        val vault = testVault(this)
        val before = vault.membership()

        val network = P2pNetwork(
            relayUrl = "  wss://relay.example.com  ",
            iceUrl = "",
            accessToken = "secret-token",
            allowLan = false,
        )
        vault.putNetwork(vault.groupId, network).bind()

        val expected = P2pNetwork(
            relayUrl = "wss://relay.example.com",
            iceUrl = null,
            accessToken = "secret-token",
            allowLan = false,
        )
        val after = vault.membership()
        assertEquals(expected, after.network)
        assertEquals(before.copy(network = expected), after)
        assertEquals(expected, vault.getNetwork(vault.groupId).first())
    }

    @Test
    fun `leaving the group takes this device out of it`() = runTest {
        val vault = testVault(this)
        vault.addPeer(label = "Phone")

        vault.leave()(vault.groupId, TEST_MASTER_PASSWORD).bind()
        advanceUntilIdle()

        assertEquals(null, vault.device.db.p2pGroupQueries.getByGroupId(vault.groupId.id).executeAsOneOrNull())
        assertTrue(vault.getDevices(vault.groupId).first().isEmpty())
    }

    @Test
    fun `leaving the group revokes this device and publishes it before it forgets the group`() = runTest {
        val vault = testVault(this)
        val peer = vault.addPeer(label = "Phone")
        val self = vault.membership().device.deviceId
        val validator = vault.validator()

        val rostersAtDissolve = mutableListOf<Roster>()
        val leave = vault.leave(
            dissolve = { groupId ->
                assertEquals(1, vault.retainedPublications)
                assertEquals(listOf(groupId.id), vault.published)
                rostersAtDissolve += vault.roster()
                vault.dissolve(groupId)
            },
        )
        leave(vault.groupId, TEST_MASTER_PASSWORD).bind()
        advanceUntilIdle()

        // The other devices accept the revocation: this device signed it while active.
        val roster = validator.validate(rostersAtDissolve.single())
        val revocation = roster.revoked.single()
        assertEquals(self, revocation.deviceId)
        assertEquals(self, revocation.by)
        val activity = vault.activity(roster)
        assertFalse(activity.isActive(self))
        assertTrue(activity.isActive(peer.deviceId))
        assertEquals(null, vault.device.db.p2pGroupQueries.getByGroupId(vault.groupId.id).executeAsOneOrNull())
        assertEquals(0, vault.retainedPublications)
    }

    @Test
    fun `leaving the group forgets it even if publishing fails`() = runTest {
        val vault = testVault(this)
        vault.addPeer(label = "Phone")

        val leave = vault.leave(
            publishLive = { _, _ -> error("The relay is down") },
        )
        leave(vault.groupId, TEST_MASTER_PASSWORD).bind()
        advanceUntilIdle()

        assertEquals(null, vault.device.db.p2pGroupQueries.getByGroupId(vault.groupId.id).executeAsOneOrNull())
        assertEquals(0, vault.retainedPublications)
    }

    @Test
    fun `leaving the group needs the master password while this device is in it`() = runTest {
        val vault = testVault(this)
        vault.addPeer(label = "Phone")

        listOf(null, "not the password").forEach { password ->
            assertFailsWith<IllegalStateException> {
                vault.leave()(vault.groupId, password).bind()
            }
        }
        assertTrue(vault.activity(vault.roster()).isActive(vault.membership().device.deviceId))
    }

    @Test
    fun `stored transports map to the domain`() {
        assertEquals(DKeyguardTransport.LAN, keyguardTransportOf("lan"))
        assertEquals(DKeyguardTransport.STUN, keyguardTransportOf("stun"))
        assertEquals(DKeyguardTransport.TURN, keyguardTransportOf("turn"))
        assertEquals(DKeyguardTransport.RELAY, keyguardTransportOf("relay"))
        assertEquals(null, keyguardTransportOf("carrier-pigeon"))
    }

    private companion object {
        val FINGERPRINT = Regex("^[0-9a-f]{4}( [0-9a-f]{4}){3}$")
    }
}

/** Linked apps on an in-memory database, and the device use cases on them. */
private class TestVault(
    testScope: TestScope,
) {
    /** The app, linked as the device "Laptop". */
    val device = AppLinkTestDevice("Laptop")

    val queuedSyncs = mutableListOf<P2pGroupId>()

    private val db = device.dbManager

    private val queueSync = QueueP2pSync { groupId ->
        ioEffect {
            queuedSyncs += groupId
        }
    }

    private val windowCoroutineScope = object : WindowCoroutineScope, CoroutineScope by testScope {}

    val getDevices = GetKeyguardDevicesImpl(
        db = db,
        crypto = device.crypto,
    )

    val revoke = RevokeKeyguardDeviceImpl(
        db = db,
        crypto = device.crypto,
        authorityKeys = device.authorityKeys,
        queueSync = queueSync,
        windowCoroutineScope = windowCoroutineScope,
    )

    val putLabel = PutKeyguardDeviceLabelImpl(
        db = db,
        membershipStore = P2pMembershipStore(db),
        crypto = device.crypto,
        queueSync = queueSync,
        windowCoroutineScope = windowCoroutineScope,
    )

    /** The group ids whose stored state was handed to a live session. */
    val published = mutableListOf<String>()

    var retainedPublications = 0

    /** Forgets the group, as the app does when it unlinks. */
    val dissolve: suspend (P2pGroupId) -> Unit = {
        device.service.dissolve()
    }

    fun leave(
        dissolve: suspend (P2pGroupId) -> Unit = this.dissolve,
        publishLive: suspend (String, Long) -> Unit = { groupId, _ -> published += groupId },
    ) = LeaveKeyguardGroupImpl(
        db = db,
        crypto = device.crypto,
        authorityKeys = device.authorityKeys,
        dissolve = dissolve,
        retainLive = { groupId ->
            retainedPublications++
            object : KeyguardSessionPublication {
                override suspend fun publish(timeoutMs: Long): Boolean {
                    publishLive(groupId, timeoutMs)
                    return true
                }

                override suspend fun close() {
                    retainedPublications--
                }
            }
        },
    )

    val putNetwork = PutKeyguardNetworkSettingsImpl(
        membershipStore = P2pMembershipStore(db),
    )

    val getNetwork = GetKeyguardNetworkSettingsImpl(
        membershipStore = P2pMembershipStore(db),
    )

    /** The sync group of linked apps. */
    val groupId: P2pGroupId = P2pGroupId.AppLink

    fun membership(): P2pMembership = device.membership()

    /** The stored roster of the group. */
    fun roster(): Roster = KeyguardSyncStore(device.db, groupId.id).loadRoster(membership().founderId)

    /** Drops the forged records of a roster of the group. */
    fun validator(): RosterValidator = RosterValidator(device.crypto, membership().groupId)

    /** Which devices of [roster] are active. */
    fun activity(roster: Roster): RosterActivity = RosterActivity(roster, device.crypto::sha256)

    /** Admits a new device named [label] on behalf of this one, or of [by]. */
    suspend fun addPeer(label: String, by: P2pDevice? = null): P2pDevice {
        val peer = device.newDevice(label = label)
        if (by == null) {
            device.admit(peer)
        } else {
            val store = KeyguardSyncStore(device.db, groupId.id)
            val stored = store.loadRoster(membership().founderId)
            val inviter = stored.devices.single { it.id == by.deviceId }
            val addedAt = Hlc(wall = inviter.addedAt.wall + 1, counter = 0, node = by.deviceId)
            val signer = RosterSigner(device.crypto, membership().groupId)
            val record = signer.selfSignedRecord(peer, addedAt, addedBy = by.deviceId)
            val admitted = openTestAuthority(by).use { signer.admit(record, it.seed) }
            store.saveRoster(stored.copy(devices = stored.devices + admitted))
        }
        return peer
    }
}

private suspend fun testVault(scope: TestScope): TestVault = TestVault(testScope = scope)
    .also { it.device.link() }
