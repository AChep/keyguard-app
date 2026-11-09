package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.SimDevice
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.schema
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PublishTest {
    private val publisher = LocalEditPublisher()
    private val random = Random(12)
    private var time = 7_000_000L

    private fun devices(count: Int): List<SimDevice> =
        TestGroup(TestCrypto(12), size = count, random = random).simDevices { time++ }

    private fun forwarder(name: String = "forwarder") =
        ForwarderBodies.forwarder(name, "anonaddy", "apiKey" to "key", "domain" to "example.com")

    /** The stamp of the edit that last changed each part, as the body shows it. */
    private fun SyncEntry.partStamps(): Map<String, Hlc> = (schema().parts.map { it.key } + EntryVersion.REMAINDER)
        .associateWith { key -> requireNotNull(sourceOf(key)?.parts?.get(key)) }

    private fun JsonObject.at(path: String): Any? = path.removePrefix("/").split('/')
        .fold<String, Any?>(this) { node, segment -> (node as? Map<*, *>)?.get(segment) }

    @Test
    fun `a new entry stamps every part it has`() {
        val (a) = devices(1)
        val id = random.nextUuid()
        val entry = a.edit(id, forwarder())
        assertEquals(VersionVector.of(listOf(entry.stamp)), entry.vv)
        assertEquals(setOf("/name", "/data", EntryVersion.REMAINDER), entry.versions.single().parts.keys)
        assertTrue(entry.partStamps().values.all { it == entry.stamp })
        assertEquals(a.id, entry.versions.single().writer)
        assertEquals(forwarder(), entry.body)
    }

    @Test
    fun `a local edit never clobbers concurrent incoming parts`() {
        val (a, b) = devices(2)
        val id = random.nextUuid()
        a.edit(id, forwarder())
        a.receive(b)
        b.receive(a)
        // B opens the forwarder; meanwhile A changes the key and B merges that.
        val applied = b.view.getValue(id)
        a.edit(id, ForwarderBodies.setData(a.view.getValue(id), "apiKey", "from a"))
        b.receive(a)
        val current = b.entryOf(id)
        // B saves a new name from the stale form.
        val local = ForwarderBodies.rename(applied, "from b")
        val published = publisher
            .publish(id, EntryKind.EMAIL_FORWARDER, current, applied, local, b.clock, b.signer, b.budget())
        assertEquals(JsonPrimitive("from a"), published.body?.at("/data/apiKey"))
        assertEquals(JsonPrimitive("from b"), published.body?.at("/name"))
        assertEquals(current.partStamps().getValue("/data"), published.partStamps().getValue("/data"))
        assertEquals(published.stamp, published.partStamps().getValue("/name"))
        assertTrue(published.vv.covers(current.vv))
        assertFalse(current.vv.covers(published.vv))
    }

    @Test
    fun `only the changed parts are restamped`() {
        val (a) = devices(1)
        val id = random.nextUuid()
        val created = a.edit(id, forwarder())
        val edited = a.edit(id, ForwarderBodies.setData(a.view.getValue(id), "domain", "other.com"))
        val changed = edited.partStamps().filter { (key, stamp) -> stamp != created.partStamps()[key] }.keys
        assertEquals(setOf("/data"), changed)
    }

    @Test
    fun `the type and the data change as one part`() {
        val (a) = devices(1)
        val id = random.nextUuid()
        val created = a.edit(id, forwarder())
        val edited = a.edit(id, ForwarderBodies.retype(a.view.getValue(id), "simplelogin"))
        val changed = edited.partStamps().filter { (key, stamp) -> stamp != created.partStamps()[key] }.keys
        assertEquals(setOf("/data"), changed)
    }

    @Test
    fun `saving an unchanged body publishes nothing`() {
        val (a) = devices(1)
        val id = random.nextUuid()
        val created = a.edit(id, forwarder())
        val body = a.view.getValue(id)
        val republished = publisher
            .publish(id, EntryKind.EMAIL_FORWARDER, created, body, body, a.clock, a.signer, a.budget())
        assertSame(created, republished)
    }

    @Test
    fun `fields outside every part follow the latest edit that changed them`() {
        val (a, b) = devices(2)
        val id = random.nextUuid()
        a.edit(id, forwarder())
        b.receive(a)
        val applied = b.view.getValue(id)
        a.edit(id, JsonObject(a.view.getValue(id) + ("extra" to JsonPrimitive("from a"))))
        b.receive(a)
        // B's edit leaves the unknown field alone, so A's value stays.
        val renamed = publisher.publish(
            id = id,
            kind = EntryKind.EMAIL_FORWARDER,
            current = b.entryOf(id),
            applied = applied,
            local = ForwarderBodies.rename(applied, "renamed"),
            clock = b.clock,
            signer = b.signer,
            budget = b.budget(),
        )
        assertEquals(JsonPrimitive("from a"), renamed.body?.at("/extra"))
        // An edit of only an unknown field still makes a new version.
        val touched = publisher.publish(
            id = id,
            kind = EntryKind.EMAIL_FORWARDER,
            current = renamed,
            applied = renamed.body,
            local = JsonObject(requireNotNull(renamed.body) + ("extra" to JsonPrimitive("x"))),
            clock = b.clock,
            signer = b.signer,
            budget = b.budget(),
        )
        assertTrue(touched.vv.covers(renamed.vv))
        assertFalse(renamed.vv.covers(touched.vv))
        assertEquals(renamed.partStamps() - EntryVersion.REMAINDER, touched.partStamps() - EntryVersion.REMAINDER)
        assertEquals(JsonPrimitive("x"), touched.body?.at("/extra"))
    }

    @Test
    fun `concurrent edits of the config part keep the newer one whole`() {
        val (a, b) = devices(2)
        val id = random.nextUuid()
        a.edit(id, forwarder())
        b.receive(a)
        a.edit(id, ForwarderBodies.setData(a.view.getValue(id), "apiKey", "from a"))
        b.edit(id, ForwarderBodies.setData(b.view.getValue(id), "domain", "from-b.com"))
        a.receive(b)
        b.receive(a)
        assertEquals(a.entries, b.entries)
        val data = ForwarderBodies.data(a.view.getValue(id))
        // The data is the one of a single edit: either A's key or B's domain, never both.
        val fromA = mapOf("apiKey" to "from a", "domain" to "example.com")
        val fromB = mapOf("apiKey" to "key", "domain" to "from-b.com")
        assertTrue(data == fromA || data == fromB, "mixed config: $data")
    }

    @Test
    fun `a body that has no data is saved as it is`() {
        val (a) = devices(1)
        val id = random.nextUuid()
        val body = buildJsonObject { put("name", "bare") }
        val entry = a.edit(id, body)
        assertEquals(body, entry.body)
        assertEquals(setOf("/name", "/data", EntryVersion.REMAINDER), entry.partStamps().keys)
    }

    @Test
    fun `an edit replaces every version it saw`() {
        val (a, b) = devices(2)
        val id = random.nextUuid()
        a.edit(id, forwarder())
        b.receive(a)
        a.edit(id, ForwarderBodies.rename(a.view.getValue(id), "from a"))
        b.edit(id, ForwarderBodies.setData(b.view.getValue(id), "domain", "from-b.com"))
        a.receive(b)
        assertEquals(2, a.entryOf(id).versions.size)
        val edited = a.edit(id, ForwarderBodies.setData(a.view.getValue(id), "apiKey", "both"))
        assertEquals(1, edited.versions.size)
        assertEquals(JsonPrimitive("from a"), edited.body?.at("/name"))
        assertEquals(JsonPrimitive("from-b.com"), edited.body?.at("/data/domain"))
    }
}
