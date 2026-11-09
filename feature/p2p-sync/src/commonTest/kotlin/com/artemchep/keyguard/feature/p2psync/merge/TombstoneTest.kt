package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.SimDevice
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TombstoneTest {
    private val merger = EntryMerger
    private val random = Random(8)
    private var time = 5_000_000L

    private fun devices(count: Int): List<SimDevice> =
        TestGroup(TestCrypto(8), size = count, random = random).simDevices { time++ }

    private fun SimDevice.editName(id: String, value: String) =
        edit(id, ForwarderBodies.rename(view.getValue(id), value))

    private fun SimDevice.editKey(id: String, key: String, value: String) =
        edit(id, ForwarderBodies.setData(view.getValue(id), key, value))

    private fun SimDevice.key(id: String, key: String): String? = ForwarderBodies.data(view[id])[key]

    @Test
    fun `a delete that saw the edit wins`() {
        val (a, b) = devices(2)
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("item"))
        a.share(b)
        a.editName(id, "renamed")
        a.share(b)
        b.delete(id)
        b.share(a)
        assertTrue(a.entryOf(id).deleted)
        assertFalse(id in a.view)
        assertEquals(a.entries, b.entries)
    }

    @Test
    fun `an edit the delete never saw brings the whole entry back`() {
        val (a, b) = devices(2)
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("item", "anonaddy", "apiKey" to "old", "domain" to "alice"))
        a.share(b)
        a.delete(id)
        b.editKey(id, "apiKey", "new key")
        a.share(b)
        b.share(a)
        assertEquals(a.entries, b.entries)
        val entry = a.entryOf(id)
        assertFalse(entry.deleted)
        assertEquals("new key", a.key(id, "apiKey"))
        assertEquals("alice", a.key(id, "domain"))
        assertEquals("item", a.name(id))
    }

    @Test
    fun `an edit the delete never saw comes back as it was, in any delivery order`() {
        val (a, b, c) = devices(3)
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("item"))
        a.share(b, c)
        val notes = a.editName(id, "seen by the delete")
        val tombstone = a.delete(id)
        val password = b.editKey(id, "apiKey", "concurrent")
        // Device C hears of the delete first; device B of the edit first.
        c.receive(a)
        c.receive(b)
        b.receive(a)
        assertEquals(c.entryOf(id), b.entryOf(id))
        val orders = listOf(
            merger.merge(merger.merge(notes, password), tombstone),
            merger.merge(merger.merge(notes, tombstone), password),
            merger.merge(merger.merge(tombstone, password), notes),
        )
        orders.forEach { assertEquals(c.entryOf(id), it) }
        assertFalse(c.entryOf(id).deleted)
        // The delete took the rename it saw with it; the concurrent edit is the whole record.
        assertEquals("item", c.name(id))
        assertEquals("concurrent", c.key(id, "apiKey"))
    }

    @Test
    fun `concurrent deletes merge into one tombstone`() {
        val (a, b) = devices(2)
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("item"))
        a.share(b)
        val first = a.delete(id)
        val second = b.delete(id)
        val merged = merger.merge(first, second)
        assertTrue(merged.deleted)
        assertEquals(maxOf(first.stamp, second.stamp), merged.stamp)
        assertEquals(first.vv.join(second.vv), merged.vv)
        assertEquals(emptyList(), merged.versions)
        assertEquals(2, merged.deletes.size)
    }

    @Test
    fun `two live versions merge into a tombstone when each one's deletes saw the other's edits`() {
        val devices = devices(4)
        val p = devices[0]
        val q = devices[1]
        val r = devices[2]
        val s = devices[3]
        val id = random.nextUuid()
        p.edit(id, ForwarderBodies.forwarder("item"))
        p.share(q, r, s)
        q.editName(id, "q")
        r.editName(id, "r")
        q.share(s)
        s.delete(id) // saw Q's edit
        r.share(p)
        p.delete(id) // saw R's edit
        p.share(q) // Q's edit survives P's delete
        s.share(r) // R's edit survives S's delete
        val left = q.entryOf(id)
        val right = r.entryOf(id)
        assertFalse(left.deleted)
        assertFalse(right.deleted)
        assertTrue(merger.merge(left, right).deleted)
        assertTrue(merger.merge(right, left).deleted)
    }

    @Test
    fun `deleting a deleted entry changes nothing and a local edit brings it back`() {
        val (a) = devices(1)
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("item"))
        val body = a.view.getValue(id)
        val tombstone = a.delete(id)
        assertEquals(tombstone, a.delete(id))
        // The app still had the row open and saves it.
        val revived = a.edit(id, ForwarderBodies.rename(body, "back"))
        assertFalse(revived.deleted)
        assertTrue(revived.vv.covers(tombstone.vv))
        assertFalse(tombstone.vv.covers(revived.vv))
        // The new version saw the delete, so it replaces it.
        assertEquals(emptyList(), revived.deletes)
        assertEquals("back", a.name(id))
    }

    @Test
    fun `a delete keeps nothing of the record`() {
        val (a, b) = devices(2)
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("item", "anonaddy", "apiKey" to "secret-key"))
        a.share(b)
        b.editName(id, "renamed")
        b.share(a)
        a.delete(id)
        a.share(b)
        val entry = b.entryOf(id)
        assertTrue(entry.deleted)
        assertNull(entry.body)
        assertEquals(emptyList(), entry.versions)
        val stored = P2pJson.encodeToString(SyncEntry.serializer(), entry)
        assertFalse("secret-key" in stored, stored)
        assertFalse("renamed" in stored, stored)
    }
}
