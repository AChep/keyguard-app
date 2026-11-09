package com.artemchep.keyguard.provider.keyguard.applink

import app.cash.sqldelight.Query
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.DeviceFeature
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeyguardNetworkSettingsImpl
import com.artemchep.keyguard.provider.keyguard.P2pMembershipStore
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingError
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingException
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardInbox
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// One function per scenario.
@Suppress("TooManyFunctions")
class AppLinkSyncTest {
    @Test
    fun `nothing is recorded while the app is not linked`() = runTest {
        val a = AppLinkTestDevice("A")
        a.addForwarder("Work")

        assertEquals(DAppLinkState.NotLinked, a.service.observeState().first())
        assertTrue(a.entries().isEmpty())
        assertTrue(KeyguardSyncStore(a.db, P2pGroupId.APP_LINK_ID).loadEntries().isEmpty())
    }

    @Test
    fun `starting a link publishes the forwarders this app already has`() = runTest {
        val a = AppLinkTestDevice("A")
        a.addForwarder("Work", "simplelogin", "apiKey" to "secret")

        a.link()

        assertEquals(DAppLinkState.Alone, a.service.observeState().first())
        val entry = a.entries().values.single()
        assertEquals(EntryKind.EMAIL_FORWARDER, entry.knownKind)
        assertEquals(a.forwarderSyncId("Work"), entry.id)
        assertEquals("1", a.forwarder("Work").id)
    }

    @Test
    fun `starting a link commits it and the records it publishes at once`() = runTest {
        val a = AppLinkTestDevice("A")
        a.addForwarder("Work")
        a.addForwarder("Home")

        val updates = a.entryUpdatesDuring { a.link() }

        assertEquals(1, updates)
        assertEquals(2, a.entries().size)
    }

    @Test
    fun `joining a link commits it and the records it writes at once`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        a.addForwarder("Work")
        b.addForwarder("Home")
        a.link()
        b.time = a.time + 1

        val updates = b.entryUpdatesDuring { b.join(a) }

        assertEquals(1, updates)
        assertEquals(setOf("Work", "Home"), b.forwarderList().map { it.name }.toSet())
    }

    @Test
    fun `linked apps sync their forwarders both ways`() = runTest {
        val (a, b) = linkedPair {
            a.addForwarder("Work", "simplelogin", "apiKey" to "a")
            b.addForwarder("Home", "duckduckgo", "apiKey" to "b")
        }
        a.exchangeWith(b)

        assertEquals(DAppLinkState.Linked(peers = 1), a.service.observeState().first())
        assertEquals(DAppLinkState.Linked(peers = 1), b.service.observeState().first())
        assertEquals(setOf("Work", "Home"), a.forwarderList().map { it.name }.toSet())
        assertEquals(setOf("Work", "Home"), b.forwarderList().map { it.name }.toSet())
        // Only the sync id is shared; the generator uses each device's local row id.
        assertEquals(a.forwarderSyncId("Home"), b.forwarderSyncId("Home"))
        assertNotEquals(a.forwarder("Home").id, b.forwarder("Home").id)
        assertEquals(mapOf("apiKey" to "b"), a.forwarder("Home").data)
    }

    @Test
    fun `an edit updates the forwarder in place and a delete removes it`() = runTest {
        val (a, b) = linkedPair {
            a.addForwarder("Work", "simplelogin", "apiKey" to "a")
        }
        a.exchangeWith(b)
        val before = b.forwarder("Work")

        a.time += 1_000
        a.editForwarder(a.forwarder("Work"), name = "Office")
        a.exchangeWith(b)

        val after = b.forwarder("Office")
        // The generator refers to the forwarder by its row.
        assertEquals(before.id, after.id)

        a.time += 1_000
        a.removeForwarder(a.forwarder("Office"))
        a.exchangeWith(b)

        assertTrue(b.forwarderList().isEmpty())
    }

    @Test
    fun `linking identical forwarders preserves local references when the sync id changes`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        a.addForwarder("Work", "simplelogin", "apiKey" to "same")
        b.addForwarder("Work", "simplelogin", "apiKey" to "same")
        val before = b.forwarder("Work")
        val oldSyncId = b.forwarderSyncId("Work")

        a.link()
        b.time = a.time + 1
        b.join(a)
        a.exchangeWith(b)

        assertEquals(1, a.forwarderList().size)
        assertEquals(1, b.forwarderList().size)
        assertEquals(a.forwarderSyncId("Work"), b.forwarderSyncId("Work"))
        assertNotEquals(oldSyncId, b.forwarderSyncId("Work"))
        assertEquals(before.id, b.forwarder("Work").id)

        // A form opened before linking still edits and deletes the same local row.
        b.time += 1_000
        b.editForwarder(before, name = "Office")
        b.exchangeWith(a)

        assertEquals(listOf("Office"), a.forwarderList().map { it.name })
        assertEquals(before.id, b.forwarder("Office").id)

        b.time += 1_000
        b.removeForwarder(before)
        b.exchangeWith(a)

        assertTrue(a.forwarderList().isEmpty())
        assertTrue(b.forwarderList().isEmpty())
    }

    @Test
    fun `a device that does not sync forwarders neither sends nor applies them`() = runTest {
        val (a, b) = linkedPair {
            b.addForwarder("Private", "duckduckgo", "apiKey" to "b")
        }
        b.switches.set(DAppLinkSyncCategory.EMAIL_FORWARDERS, false).bind()
        a.addForwarder("Work", "simplelogin", "apiKey" to "a")

        assertTrue(b.envelope().entries.isEmpty())
        a.exchangeWith(b)

        assertEquals(listOf("Private"), b.forwarderList().map { it.name })
        assertEquals(listOf("Work"), a.forwarderList().map { it.name })
    }

    @Test
    fun `turning the sync back on merges each part by the time of its edit`() = runTest {
        val (a, b) = linkedPair {
            a.addForwarder("Work", "simplelogin", "apiKey" to "old")
        }
        a.exchangeWith(b)
        b.switches.set(DAppLinkSyncCategory.EMAIL_FORWARDERS, false).bind()

        // While off, B renames the forwarder; later, A changes its key.
        b.time += 10_000
        b.editForwarder(b.forwarder("Work"), name = "Office")
        a.time += 20_000
        a.editForwarder(a.forwarder("Work"), data = mapOf("apiKey" to "new"))
        a.exchangeWith(b)
        assertEquals(mapOf("apiKey" to "old"), b.forwarder("Office").data)

        b.switches.set(DAppLinkSyncCategory.EMAIL_FORWARDERS, true).bind()
        a.exchangeWith(b)
        a.exchangeWith(b)

        for (device in listOf(a, b)) {
            val forwarder = device.forwarderList().single()
            assertEquals("Office", forwarder.name, device.name)
            assertEquals(mapOf("apiKey" to "new"), forwarder.data, device.name)
        }
    }

    @Test
    fun `the newer edit of the same part wins once the sync is on again`() = runTest {
        val (a, b) = linkedPair {
            a.addForwarder("Work")
        }
        a.exchangeWith(b)
        b.switches.set(DAppLinkSyncCategory.EMAIL_FORWARDERS, false).bind()

        b.time += 10_000
        b.editForwarder(b.forwarder("Work"), name = "Older")
        a.time += 20_000
        a.editForwarder(a.forwarder("Work"), name = "Newer")

        b.switches.set(DAppLinkSyncCategory.EMAIL_FORWARDERS, true).bind()
        a.exchangeWith(b)
        a.exchangeWith(b)

        assertEquals(listOf("Newer"), a.forwarderList().map { it.name })
        assertEquals(listOf("Newer"), b.forwarderList().map { it.name })
    }

    @Test
    fun `what a removed device writes before every device knows stays`() = runTest {
        val (a, b) = linkedPair {
            a.addForwarder("Work", "simplelogin", "apiKey" to "a")
        }
        val c = AppLinkTestDevice("C")
        c.time = a.time + 1
        c.join(a)
        b.exchangeWith(a)
        c.exchangeWith(a)
        a.time += 1_000
        a.revoke(b)

        // B does not know yet: it adds a forwarder, and C, which does not know either, takes it.
        b.time = a.time + 1_000
        b.addForwarder("Late", "duckduckgo", "apiKey" to "b")
        c.receive(b)

        // C learns of the removal, and keeps what it took; A takes it from C.
        c.receive(a)
        assertEquals(setOf("Work", "Late"), c.forwarderList().map { it.name }.toSet())
        a.receive(c)
        assertEquals(setOf("Work", "Late"), a.forwarderList().map { it.name }.toSet())
        assertEquals(a.entries(), c.entries())
        // Nothing comes from B itself any more.
        b.addForwarder("After", "duckduckgo", "apiKey" to "b")
        a.receive(b)
        assertEquals(setOf("Work", "Late"), a.forwarderList().map { it.name }.toSet())
    }

    @Test
    fun `a deleted forwarder leaves nothing of itself in the sync state`() = runTest {
        val (a, b) = linkedPair {
            a.addForwarder("Work", "simplelogin", "apiKey" to "secret-key")
        }
        b.exchangeWith(a)
        b.editForwarder(b.forwarder("Work"), name = "Renamed")
        a.exchangeWith(b)
        a.removeForwarder(a.forwarder("Renamed"))
        b.exchangeWith(a)

        listOf(a, b).forEach { device ->
            assertTrue(device.forwarderList().isEmpty())
            val stored = device.db.p2pSyncEntryQueries
                .getByGroupId(groupId = P2pGroupId.APP_LINK_ID)
                .executeAsList()
                .filter { it.kind == EntryKind.EMAIL_FORWARDER.wireName }
            assertEquals(1, stored.size)
            assertEquals("[]", stored.single().versions)
            assertFalse("secret-key" in stored.single().deletes)
        }
    }

    @Test
    fun `a device linked to others can not join another link`() = runTest {
        val (a, _) = linkedPair {}
        val c = AppLinkTestDevice("C")
        c.link()

        val error = assertFailsWith<KeyguardPairingException> { a.join(c) }

        assertEquals(KeyguardPairingError.ALREADY_LINKED, error.error)
    }

    @Test
    fun `a device alone in its link may join another one`() = runTest {
        val a = AppLinkTestDevice("A")
        a.link()
        val c = AppLinkTestDevice("C")
        c.link()

        a.join(c)

        assertEquals(c.membership().groupId, a.membership().groupId)
        assertEquals(DAppLinkState.Linked(peers = 1), a.service.observeState().first())
    }

    @Test
    fun `unlinking keeps the forwarders on the device`() = runTest {
        val (a, b) = linkedPair {
            a.addForwarder("Work")
        }
        a.exchangeWith(b)

        b.service.dissolve()

        assertEquals(DAppLinkState.NotLinked, b.service.observeState().first())
        assertEquals(listOf("Work"), b.forwarderList().map { it.name })
        assertNull(b.service.membership())
    }

    @Test
    fun `linking through the hosted relay needs a purchase`() = runTest {
        listOf(Premium.NONE, Premium.INCLUDED).forEach { premium ->
            AppLinkTestDevice("A", premium = premium).use { a ->
                assertFailsWith<KeyguardPairingException> { a.link() }
                assertFailsWith<KeyguardPairingException> { a.service.checkJoinable() }
                assertNull(a.service.membership())
            }
        }
    }

    @Test
    fun `existing links still sync and allow removal and unlinking after a purchase expires`() = runTest {
        val (a, b) = linkedPair {}
        a.premium.value = Premium.INCLUDED
        b.premium.value = Premium.NONE
        a.addForwarder("Work")
        a.exchangeWith(b)
        assertEquals(listOf("Work"), b.forwarderList().map { it.name })
        assertFailsWith<KeyguardPairingException> { a.service.checkJoinable() }

        a.revoke(b)
        a.exchangeWith(b)
        assertEquals(DAppLinkState.Removed, b.service.observeState().first())
        b.service.dissolve()
        assertNull(b.service.membership())
    }

    /** Two apps: A starts a link and B joins it, after [setUp] ran on both unlinked apps. */
    @Test
    fun `a device signs its record again with the caps of its build, and the others learn them`() = runTest {
        val (a, b) = linkedPair {}
        // The record of an older build, which knew only the forwarders.
        val old = DeviceCaps(rules = DeviceCaps.RULES, kinds = listOf(EntryKind.EMAIL_FORWARDER.wireName))
        a.time += 1_000
        assertTrue(a.signOwnRecord(old))
        b.receive(a)
        assertEquals(old, b.recordOf(a).caps)
        val before = a.recordOf(a)

        // The next pass of the updated build signs it again, as a rename does.
        a.time += 1_000
        a.materialize()
        val after = a.recordOf(a)
        assertEquals(DeviceCaps.CURRENT, after.caps)
        assertEquals(before.label, after.label)
        assertTrue(after.updatedAt > before.updatedAt)
        b.receive(a)
        assertEquals(after, b.recordOf(a))

        // Up to date, the record stays as it is; the order of the kinds does not count.
        a.time += 1_000
        a.materialize()
        assertEquals(after, a.recordOf(a))
        val reordered = DeviceCaps.CURRENT.copy(kinds = DeviceCaps.CURRENT.kinds.reversed())
        assertTrue(a.signOwnRecord(reordered))
        val kept = a.recordOf(a)
        a.time += 1_000
        a.materialize()
        assertEquals(kept, a.recordOf(a))
    }

    @Test
    fun `a device lists relay storage in its record while its user allows it`() = runTest {
        val (a, b) = linkedPair {}
        suspend fun allow(value: Boolean) {
            PutKeyguardNetworkSettingsImpl(P2pMembershipStore(a.dbManager))(
                P2pGroupId.AppLink,
                a.membership().network.copy(relayStorage = value),
            ).bind()
            a.time += 1_000
            a.materialize()
            b.receive(a)
        }
        assertEquals(emptyList(), a.recordOf(a).caps.features)

        allow(true)
        assertEquals(DeviceCaps.current(relayStorage = true), a.recordOf(a).caps)
        assertEquals(listOf(DeviceFeature.RELAY_STORAGE), b.recordOf(a).caps.features)

        allow(false)
        assertEquals(DeviceCaps.CURRENT, a.recordOf(a).caps)
        assertEquals(emptyList(), b.recordOf(a).caps.features)
    }

    @Test
    fun `an older envelope from the relay never displaces a newer one from a session`() = runTest {
        val (a, b) = linkedPair {}
        a.addForwarder("One")
        val older = a.envelope()
        a.addForwarder("Two")
        val newer = a.envelope()
        // The session hands the newer one over, then a fetch of the relay ends with the
        // older one, before a pass runs.
        b.post(a, newer)
        b.components.inbox.post(P2pGroupId.APP_LINK_ID, a.deviceId, older, source = KeyguardInbox.Source.RELAY)
        b.materialize()
        assertEquals(setOf("One", "Two"), b.forwarderList().map { it.name }.toSet())
    }

    @Test
    fun `a pass writes the object key, keeps it, and replaces it after a removal`() = runTest {
        val (a, b) = linkedPair {}
        // Founding and joining write no key; the first pass does.
        assertNull(a.objectKey())
        a.materialize()
        val first = assertNotNull(a.objectKey())
        assertEquals(emptyList(), first.covers)

        a.time += 1_000
        a.materialize()
        b.receive(a)
        assertEquals(first, a.objectKey())
        assertEquals(first, b.objectKey())

        // The remover writes a new key in the pass the removal queues.
        a.time += 1_000
        a.revoke(b)
        a.materialize()
        val second = assertNotNull(a.objectKey())
        assertFalse(first.key.contentEquals(second.key))
        assertEquals(listOf(b.deviceId), second.covers)

        a.time += 1_000
        a.materialize()
        assertEquals(second, a.objectKey())
    }

    @Test
    fun `the object key syncs while every category is off, and has no rows`() = runTest {
        val (a, b) = linkedPair {}
        DAppLinkSyncCategory.entries.forEach { b.switches.set(it, false).bind() }
        val rows = b.rowCounts()

        a.materialize()
        b.receive(a)

        val entry = a.entries().getValue(ObjectKey.ENTRY_KEY)
        assertEquals(entry, b.entries()[ObjectKey.ENTRY_KEY])
        assertEquals(listOf(entry), b.envelope().entries)
        assertEquals(rows, b.rowCounts())
    }

    @Test
    fun `a device that joins gets the object key with the link`() = runTest {
        val (a, _) = linkedPair {}
        a.materialize()
        val c = AppLinkTestDevice("C")
        c.time = a.time + 1

        c.join(a)

        val key = assertNotNull(a.objectKey())
        assertEquals(key, c.objectKey())
        c.materialize()
        assertEquals(key, c.objectKey())
    }

    @Test
    fun `an entry of a known kind of another scope is kept and relayed, never written to rows`() = runTest {
        val (a, b) = linkedPair {}
        a.addForwarder("Work")
        val envelope = a.envelope()
        val entry = envelope.entries.single { it.knownKind == EntryKind.EMAIL_FORWARDER }
        // A build of another scope knows the kind of forwarders, but has no category for them.
        val categories = AppSyncCategories(emptyList())

        b.time = a.time + 1
        b.db.transaction {
            AppLinkSyncPass(
                database = b.db,
                membership = b.membership(),
                crypto = b.crypto,
                categories = categories,
                enabled = emptySet(),
                now = { Instant.fromEpochMilliseconds(b.time) },
            ).applyEnvelope(a.deviceId, envelope)
        }

        assertEquals(entry, b.entries()[entry.key])
        assertTrue(categories.syncs(entry, enabled = emptySet()))
        assertTrue(b.forwarderList().isEmpty())
    }

    @Test
    fun `a pass applies no entry of a batch whose roster merge it refuses`() = runTest {
        val (a, b) = linkedPair {}
        a.addForwarder("Work")
        val store = KeyguardSyncStore(b.db, P2pGroupId.APP_LINK_ID)
        val stored = store.loadRoster(b.membership().founderId)
        // Merged into the roster of B, it would hold more devices than a roster may.
        val full = a.rosterWithMore(Roster.MAX_DEVICES - 1)

        b.components.inbox.postRoster(P2pGroupId.APP_LINK_ID, a.deviceId, full)
        b.components.inbox.post(P2pGroupId.APP_LINK_ID, a.deviceId, a.envelope())
        b.materialize()
        assertTrue(b.forwarderList().isEmpty())
        assertEquals(stored, store.loadRoster(b.membership().founderId))

        // The same entries with a roster that merges are applied.
        b.receive(a)
        assertEquals(listOf("Work"), b.forwarderList().map { it.name })
    }

    @Test
    fun `a pass validates the roster once, however many envelopes it merges`() = runTest {
        val (a, b) = linkedPair {
            a.addForwarder("Work")
        }
        a.exchangeWith(b)
        val envelope = a.envelope()

        // B holds every version of the envelope already: only the roster needs checks.
        fun verifiesToMerge(times: Int): Int {
            val crypto = CountingCrypto(b.crypto)
            b.db.transaction {
                val pass = AppLinkSyncPass(
                    database = b.db,
                    membership = b.membership(),
                    crypto = crypto,
                    categories = AppSyncCategories(emptyList()),
                    enabled = emptySet(),
                    now = { Instant.fromEpochMilliseconds(b.time) },
                )
                repeat(times) { pass.applyEnvelope(a.deviceId, envelope) }
            }
            return crypto.verifies
        }

        assertTrue(verifiesToMerge(1) > 0)
        assertEquals(verifiesToMerge(1), verifiesToMerge(3))
    }

    /** How many times the stored entries of the link change while the [block] runs. */
    private suspend fun AppLinkTestDevice.entryUpdatesDuring(block: suspend () -> Unit): Int {
        val query = db.p2pSyncEntryQueries.getByGroupId(P2pGroupId.APP_LINK_ID)
        var updates = 0
        val listener = Query.Listener { updates += 1 }
        query.addListener(listener)
        try {
            block()
        } finally {
            query.removeListener(listener)
        }
        return updates
    }

    /** The object key this device stores, or `null` if it has no valid one. */
    private fun AppLinkTestDevice.objectKey(): ObjectKey? = ObjectKey.fromEntry(entries()[ObjectKey.ENTRY_KEY])

    /** How many forwarders, wordlists and URL overrides this device has. */
    private suspend fun AppLinkTestDevice.rowCounts(): List<Int> =
        listOf(forwarderList().size, wordlistList().size, urlOverrideList().size)

    /** Signs the record of this device again with the [caps], as a build that has them does. */
    private fun AppLinkTestDevice.signOwnRecord(caps: DeviceCaps): Boolean =
        KeyguardSyncStore(db, P2pGroupId.APP_LINK_ID)
            .loadValidated(membership(), crypto)
            .updateSelf(now = { Instant.fromEpochMilliseconds(time) }, caps = caps)

    /** The record of the [device] as this device stores it, validated. */
    private fun AppLinkTestDevice.recordOf(device: AppLinkTestDevice): RosterDevice =
        KeyguardSyncStore(db, P2pGroupId.APP_LINK_ID)
            .loadValidated(membership(), crypto)
            .validated
            .devices
            .single { it.id == device.deviceId }

    private suspend fun linkedPair(
        setUp: suspend PairScope.() -> Unit,
    ): Pair<AppLinkTestDevice, AppLinkTestDevice> {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        PairScope(a, b).setUp()
        a.link()
        b.time = a.time + 1
        b.join(a)
        return a to b
    }

    private class PairScope(
        val a: AppLinkTestDevice,
        val b: AppLinkTestDevice,
    )
}
