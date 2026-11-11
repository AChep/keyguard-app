package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.RosterCrdt
import com.artemchep.keyguard.feature.p2psync.testing.SimDevice
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjectKeyRotationTest {
    private val crypto = TestCrypto(31)
    private var time = 7_000_000L
    private val group = TestGroup(crypto, size = 4, random = Random(31))
    private val devices = group.simDevices { time++ }
    private val a = devices[0]
    private val b = devices[1]
    private val c = devices[2]
    private val d = devices[3]

    /** Publishes a new object key if the rotation asks for one, as a sync pass does. */
    private fun SimDevice.ensureObjectKey(): SyncEntry? {
        val current = entry(ObjectKey.ENTRY_ID, EntryKind.OBJECT_KEY)
        val body = ObjectKeyRotation.next(
            current = current,
            activity = activity(),
            randomBytes = crypto::randomBytes,
        ) ?: return null
        return edit(ObjectKey.ENTRY_ID, body, EntryKind.OBJECT_KEY)
    }

    /** The object key of this device, or `null` if it has no valid one. */
    private fun SimDevice.objectKey(): ObjectKey? = ObjectKey.fromEntry(entry(ObjectKey.ENTRY_ID, EntryKind.OBJECT_KEY))

    /** The activity of the validated roster of this device. */
    private fun SimDevice.activity(): RosterActivity = group.activity(group.validator.validate(roster))

    /** `true` if the device is active in its own roster. */
    private fun SimDevice.isActive(id: String): Boolean = activity().isActive(id)

    /**
     * Fills the entries of this device with forwarders until they take every byte of the
     * budget: no entry may grow.
     */
    private fun SimDevice.fillBudget() {
        val random = Random(7)
        fun free() = EntryBudget.MAX_BYTES - entries.values.sumOf { it.usage.bytes.toLong() }
        fun pad(size: Long) {
            edit(random.nextUuid(), JsonObject(mapOf("pad" to JsonPrimitive("a".repeat(size.toInt())))))
        }

        // Every forwarder takes as many bytes besides its padding.
        val empty = free()
        pad(0)
        val overhead = empty - free()
        val chunk = 200L * 1024L
        while (free() >= 2 * (overhead + chunk)) pad(chunk)
        // The last two take the rest.
        val rest = free() - 2 * overhead
        pad(rest / 2)
        pad(rest - rest / 2)
        assertEquals(0L, free())
    }

    /** What a device sends at one moment: its roster, then its envelope. */
    private class Sent(
        val from: String,
        val roster: Roster,
        val envelope: Envelope,
    )

    private fun SimDevice.send(): Sent = Sent(id, roster, envelope())

    private fun SimDevice.take(sent: Sent) {
        roster = group.validator.merge(roster, sent.roster)
        receive(sent.envelope, from = sent.from)
    }

    @Test
    fun `a device writes a key when the group has none`() {
        assertNotNull(a.ensureObjectKey())

        val key = assertNotNull(a.objectKey())
        assertEquals(ObjectKey.KEY_BYTES, key.key.size)
        assertEquals(emptyList(), key.covers)
        // The others take it, and keep it.
        devices.drop(1).forEach { device ->
            assertEquals(emptyList(), device.receive(a).rejected)
            assertNull(device.ensureObjectKey())
            assertEquals(key, device.objectKey())
        }
    }

    @Test
    fun `the key stays while it covers every inactive device`() {
        a.ensureObjectKey()
        a.revoke(b.keys)
        a.ensureObjectKey()
        val key = a.objectKey()

        repeat(3) {
            time += 1_000
            assertNull(a.ensureObjectKey())
        }
        assertEquals(key, a.objectKey())
    }

    @Test
    fun `a removal makes a new key that covers the removed device`() {
        a.ensureObjectKey()
        val before = assertNotNull(a.objectKey())

        a.revoke(b.keys)
        assertNotNull(a.ensureObjectKey())

        val after = assertNotNull(a.objectKey())
        assertFalse(before.key.contentEquals(after.key))
        assertEquals(listOf(b.id), after.covers)
    }

    @Test
    fun `a seal that leaves a device out makes a new key that covers it`() {
        // B admitted X; A removes B and keeps X.
        val x = group.newKeys(Random(5))
        a.roster = a.roster.copy(devices = a.roster.devices + group.admit(x, by = b.keys, at = Hlc(5_000, 0, b.id)))
        a.revoke(b.keys)
        a.ensureObjectKey()
        assertTrue(a.isActive(x.id))
        assertEquals(listOf(b.id), a.objectKey()?.covers)

        // C removes B too, and its seal leaves X out: X is evicted, with no revocation of its own.
        val seal = group.signer.revoke(a.roster, b.id, c.clock.now(), c.id, c.keys.authorityPrivate) { false }
        a.roster = a.roster.copy(revoked = a.roster.revoked + seal)
        assertFalse(a.isActive(x.id))
        assertTrue(a.roster.revoked.none { it.deviceId == x.id })
        assertNotNull(a.ensureObjectKey())

        assertEquals(listOf(b.id, x.id).sorted(), a.objectKey()?.covers)
    }

    @Test
    fun `a key another active device wrote stays`() {
        a.revoke(d.keys)
        b.receive(a)
        assertNotNull(b.ensureObjectKey())

        a.receive(b)
        assertNull(a.ensureObjectKey())
        assertEquals(b.objectKey(), a.objectKey())
        assertEquals(listOf(d.id), a.objectKey()?.covers)
    }

    @Test
    fun `a key that covers its own writer is replaced once the writer is removed`() {
        // B lists itself as covered while it is still active.
        val claimed = ObjectKey(crypto.randomBytes(ObjectKey.KEY_BYTES), covers = listOf(b.id))
        b.edit(ObjectKey.ENTRY_ID, claimed.toBody(), EntryKind.OBJECT_KEY)
        a.receive(b)
        assertNull(a.ensureObjectKey())

        a.revoke(b.keys)
        assertEquals(claimed, a.objectKey())
        assertNotNull(a.ensureObjectKey())

        val after = assertNotNull(a.objectKey())
        assertFalse(claimed.key.contentEquals(after.key))
        assertEquals(listOf(b.id), after.covers)
        val source = a.entryOf(ObjectKey.ENTRY_ID, EntryKind.OBJECT_KEY).versions.single()
        assertEquals(a.id, source.writer)
    }

    @Test
    fun `a late key of a removed device that wins by its stamp is replaced again`() {
        a.ensureObjectKey()
        devices.drop(1).forEach { it.receive(a) }
        a.revoke(b.keys)
        assertNotNull(a.ensureObjectKey())
        val rotated = assertNotNull(a.objectKey())

        // B never learns of its removal, and writes a key of its own after A did. C, which
        // does not know of the removal either, relays it to A.
        val late = ObjectKey(crypto.randomBytes(ObjectKey.KEY_BYTES), covers = listOf(b.id))
        b.edit(ObjectKey.ENTRY_ID, late.toBody(), EntryKind.OBJECT_KEY)
        c.receive(b.envelope(), from = b.id)
        assertEquals(emptyList(), a.receive(c.envelope(), from = c.id).rejected)
        assertEquals(2, a.entryOf(ObjectKey.ENTRY_ID, EntryKind.OBJECT_KEY).versions.size)
        assertEquals(late, a.objectKey())

        // It counts as no key: A writes a new one, which replaces both.
        assertNotNull(a.ensureObjectKey())
        val after = assertNotNull(a.objectKey())
        assertFalse(after.key.contentEquals(late.key))
        assertFalse(after.key.contentEquals(rotated.key))
        assertEquals(listOf(b.id), after.covers)
        assertEquals(a.id, a.entryOf(ObjectKey.ENTRY_ID, EntryKind.OBJECT_KEY).versions.single().writer)

        // C learns of the removal and takes the new key; no one writes another.
        c.take(a.send())
        assertNull(c.ensureObjectKey())
        assertNull(a.ensureObjectKey())
        assertEquals(after, c.objectKey())
    }

    @Test
    fun `a device that knows of fewer removals keeps the key`() {
        a.ensureObjectKey()
        b.receive(a)
        a.revoke(c.keys)
        a.ensureObjectKey()

        // B takes the new key, but not yet the roster that removes C.
        b.receive(a.envelope(), from = a.id)
        assertTrue(b.isActive(c.id))
        assertNull(b.ensureObjectKey())

        assertEquals(a.objectKey(), b.objectKey())
        assertEquals(listOf(c.id), b.objectKey()?.covers)
    }

    @Test
    fun `a malformed or deleted key is replaced`() {
        val bodies = listOf(
            JsonObject(mapOf("key" to JsonPrimitive(P2pEncoding.base64Url(ByteArray(16))))),
            JsonObject(mapOf("covers" to JsonPrimitive("all"))),
        )
        bodies.forEach { body ->
            a.edit(ObjectKey.ENTRY_ID, body, EntryKind.OBJECT_KEY)
            assertNull(a.objectKey())
            assertNotNull(a.ensureObjectKey())
            assertNotNull(a.objectKey())
        }

        a.delete(ObjectKey.ENTRY_ID, EntryKind.OBJECT_KEY)
        assertNull(a.objectKey())
        assertNotNull(a.ensureObjectKey())
        assertNotNull(a.objectKey())
    }

    @Test
    fun `a key its writer padded to the largest body is replaced once the writer is removed`() {
        // B pads its key with a field of its own: one more covered device would not fit.
        val key = ObjectKey(crypto.randomBytes(ObjectKey.KEY_BYTES), covers = emptyList()).toBody()
        val size = CanonicalJson.encodeToByteArray(JsonObject(key + ("pad" to JsonPrimitive("")))).size
        val pad = "a".repeat(SanitizerLimits.MAX_BODY_BYTES - size)
        b.edit(ObjectKey.ENTRY_ID, JsonObject(key + ("pad" to JsonPrimitive(pad))), EntryKind.OBJECT_KEY)
        assertEquals(emptyList(), a.receive(b).rejected)

        a.revoke(b.keys)
        val entry = assertNotNull(a.ensureObjectKey())

        val after = assertNotNull(a.objectKey())
        assertEquals(after.toBody(), entry.body)
        assertEquals(listOf(b.id), after.covers)
    }

    @Test
    fun `a key that leaves a removed device uncovered is no key also while no new key fits`() {
        a.ensureObjectKey()
        val key = assertNotNull(a.objectKey())
        assertEquals(key, ObjectKey.usable(a.entry(ObjectKey.ENTRY_ID, EntryKind.OBJECT_KEY), a.activity()))
        a.fillBudget()

        // A removes B, but a new key, which covers B, takes more than the budget has left.
        a.revoke(b.keys)
        val refusal = assertFailsWith<EditRefusedException> { a.ensureObjectKey() }
        assertEquals(RejectReason.OVER_BUDGET, refusal.reason)

        // The old key stays, and A wrote it, but B knows it: A uses no key, so the object
        // worker makes no request.
        assertEquals(key, a.objectKey())
        assertTrue(a.isActive(a.id))
        assertNull(ObjectKey.usable(a.entry(ObjectKey.ENTRY_ID, EntryKind.OBJECT_KEY), a.activity()))
    }

    @Test
    fun `no device writes a key until every active device supports it`() {
        // D runs an older build, which does not know the kind.
        val record = a.roster.devices.single { it.id == d.id }
        val old = DeviceCaps(rules = DeviceCaps.RULES, kinds = DeviceCaps.CURRENT.kinds - EntryKind.OBJECT_KEY.wireName)
        val updated = group.signer.update(record, d.clock.now(), d.keys.signPrivate, caps = old)
        a.roster = RosterCrdt.merge(a.roster, Roster(founder = a.roster.founder, devices = listOf(updated)))
        assertNull(a.ensureObjectKey())

        // Once D is gone, every active device supports it.
        a.revoke(d.keys)
        assertNotNull(a.ensureObjectKey())
        assertEquals(listOf(d.id), a.objectKey()?.covers)
    }

    @Test
    fun `concurrent removals end with one key that covers both`() {
        a.ensureObjectKey()
        devices.drop(1).forEach { it.receive(a) }

        // A removes B while D removes C, and each writes a new key.
        a.revoke(b.keys)
        d.revoke(c.keys)
        assertNotNull(a.ensureObjectKey())
        assertNotNull(d.ensureObjectKey())
        assertEquals(listOf(b.id), a.objectKey()?.covers)
        assertEquals(listOf(c.id), d.objectKey()?.covers)

        // They send each other their state at once: the entries merge, and both learn of
        // both removals. The key that wins covers one of them, so both write a new one.
        val fromA = a.send()
        val fromD = d.send()
        a.take(fromD)
        d.take(fromA)
        assertEquals(2, a.entryOf(ObjectKey.ENTRY_ID, EntryKind.OBJECT_KEY).versions.size)
        assertNotEquals(listOf(b.id, c.id).sorted(), a.objectKey()?.covers)
        assertNotNull(a.ensureObjectKey())
        assertNotNull(d.ensureObjectKey())

        // Both new keys cover both removals: whichever wins, no device writes another.
        a.take(d.send())
        d.take(a.send())
        assertEquals(a.entries, d.entries)
        assertEquals(listOf(b.id, c.id).sorted(), a.objectKey()?.covers)
        assertNull(a.ensureObjectKey())
        assertNull(d.ensureObjectKey())
    }
}
