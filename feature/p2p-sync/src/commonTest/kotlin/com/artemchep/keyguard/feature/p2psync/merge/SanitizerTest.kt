package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.EntryDelete
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.RejectedEntry
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.SimDevice
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SanitizerTest {
    private val random = Random(31)
    private val group = TestGroup(TestCrypto(31), size = 3, random = random)
    private val now = 50_000_000L
    private val writer = group.id(1)
    private val signer = group.entrySigner(group.keys[1])
    private val kind = EntryKind.EMAIL_FORWARDER
    private val keys = kind.schema.parts.map { it.key } + EntryVersion.REMAINDER

    private fun sanitizer(
        roster: Roster = group.roster,
    ) = Sanitizer(roster, group.groupId, group.crypto, now)

    private fun stamp(wall: Long = now, node: String = writer) = Hlc(wall, 0, node)

    /** A new entry: one version of a forwarder, signed by the [signer]. */
    private fun entry(
        id: String = random.nextUuid(),
        stamp: Hlc = stamp(),
        history: VersionVector = VersionVector.of(listOf(stamp)),
        body: JsonObject = ForwarderBodies.forwarder("forwarder"),
        parts: Map<String, Hlc> = keys.associateWith { stamp },
        signer: EntrySigner = this.signer,
    ) = SyncEntry(
        id = id,
        kind = kind,
        versions = listOf(signer.version(id, kind, stamp, history, body, parts)),
    )

    /**
     * An entry of [id] with one version per index of [indices], all by the [signer] and none
     * building on another: each one saw a newer stamp of the writer, and an older one of the
     * third device, than the one before.
     */
    private fun concurrent(id: String, indices: IntRange): SyncEntry {
        val versions = indices.map { index ->
            val stamp = stamp(now - 10_000 + index)
            val history = VersionVector.of(listOf(stamp, stamp(now - 20_000 - index, group.id(2))))
            val body = ForwarderBodies.forwarder("forwarder $index")
            signer.version(id, kind, stamp, history, body, keys.associateWith { stamp })
        }
        return SyncEntry(id = id, kind = kind, versions = versions).normalized()
    }

    /** [entry] deleted by the [signer] at [at], having seen [history]. */
    private fun SyncEntry.deletedAt(
        at: Hlc,
        history: VersionVector = vv.witness(at),
        signer: EntrySigner = this@SanitizerTest.signer,
    ): SyncEntry = copy(deletes = deletes + signer.delete(id, kind, at, history))

    private fun envelope(vararg entries: SyncEntry) = Envelope(entries = entries.toList())

    private fun reasonOf(entry: SyncEntry, roster: Roster = group.roster): RejectReason? =
        sanitizer(roster).check(entry).reason

    private fun history(stamp: Hlc = stamp()) = VersionVector.of(listOf(stamp))

    private fun parts(stamp: Hlc = stamp(), kind: EntryKind = EntryKind.WORDLIST) =
        (kind.schema.parts.map { it.key } + EntryVersion.REMAINDER).associateWith { stamp }

    private fun wordlist() = JsonObject(mapOf("name" to JsonPrimitive("words"), "words" to JsonPrimitive("x")))

    @Test
    fun `a valid entry passes`() {
        val entry = entry()
        assertEquals(entry, sanitizer().check(entry).entry)
        val tombstone = entry.deletedAt(stamp(now + 1))
        assertNull(reasonOf(tombstone))
        assertEquals(true, sanitizer().check(tombstone).entry?.deleted)
    }

    @Test
    fun `an entry passes the same checks whichever kind it is, known or not`() {
        val kinds = EntryKind.entries.map { it.wireName } + "card-reader"
        kinds.forEach { kind ->
            val id = random.nextUuid()
            val stamp = stamp()
            val history = VersionVector.of(listOf(stamp))
            val version = signer.version(id, kind, stamp, history, JsonObject(emptyMap()), emptyMap())
            val entry = SyncEntry(id = id, kind = kind, versions = listOf(version))
            assertEquals(CheckedEntry(entry = entry, reason = null), sanitizer().check(entry), kind)
        }
    }

    @Test
    fun `ids must be UUIDs`() {
        assertEquals(RejectReason.INVALID_ID, reasonOf(entry(id = "not-a-uuid")))
    }

    @Test
    fun `stamps more than five minutes ahead are dropped`() {
        assertNull(reasonOf(entry(stamp = stamp(now + HlcClock.MAX_FUTURE_DRIFT_MS))))
        val ahead = entry(stamp = stamp(now + HlcClock.MAX_FUTURE_DRIFT_MS + 1))
        assertEquals(RejectReason.FUTURE_STAMP, reasonOf(ahead))
        // It would be taken a millisecond later.
        assertEquals(now + 1, sanitizer().check(ahead).validFrom)
        assertEquals(now + 1, sanitizer().sanitize(envelope(ahead, entry()), from = writer).validFrom)
    }

    @Test
    fun `a body larger than 256 KiB is dropped, whatever its parts`() {
        // The canonical body of a forwarder named this is one byte past the cap.
        val overhead = CanonicalJson.encodeToByteArray(ForwarderBodies.forwarder("")).size
        val name = "x".repeat(SanitizerLimits.MAX_BODY_BYTES - overhead + 1)
        assertEquals(RejectReason.OVERSIZED_BODY, reasonOf(entry(body = ForwarderBodies.forwarder(name))))
        assertNull(reasonOf(entry(body = ForwarderBodies.forwarder(name.drop(1)))))
        // The remainder counts as much as a part.
        val note = "note" to JsonPrimitive(name)
        val remainder = JsonObject(ForwarderBodies.forwarder("") + note)
        assertEquals(RejectReason.OVERSIZED_BODY, reasonOf(entry(body = remainder)))
    }

    @Test
    fun `the body cap holds for each version, so concurrent versions that fit merge`() {
        // Two devices fill most of the cap with a different part each, concurrently.
        val large = "x".repeat(SanitizerLimits.MAX_BODY_BYTES * 9 / 10)
        val (a, b) = listOf(group.keys[1], group.keys[2]).map { SimDevice(it.id, group) { now } }
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("forwarder"))
        b.receive(a)
        val renamed = a.edit(id, ForwarderBodies.rename(a.view.getValue(id), large))
        val rekeyed = b.edit(id, ForwarderBodies.setData(b.view.getValue(id), "apiKey", large))
        assertNull(reasonOf(renamed))
        assertNull(reasonOf(rekeyed))
        val merged = EntryMerger.merge(renamed, rekeyed)
        assertEquals(2, merged.versions.size)
        assertEquals(large, (merged.body?.get("name") as? JsonPrimitive)?.content)
        assertEquals(large, ForwarderBodies.data(merged.body)["apiKey"])
        // A device that holds neither takes the merged entry whole.
        val checked = sanitizer().check(merged)
        assertNull(checked.reason)
        assertEquals(merged, checked.entry)
    }

    @Test
    fun `an entry of another kind under the same id is another entry`() {
        val original = entry()
        val relabelled = original.copy(kind = EntryKind.WORDLIST.wireName)
        assertNotEquals(original.key, relabelled.key)
        // The signature covers the kind, so the copy is forged, whatever the device holds.
        assertEquals(RejectReason.FORGED, sanitizer().check(relabelled, known = original).reason)
        assertNull(sanitizer().check(original, known = original).reason)
        val merged = EnvelopeMerger().merge(mapOf(original.key to original), envelope(relabelled), writer, sanitizer())
        assertEquals(mapOf(original.key to original), merged.entries)
        // An honest wordlist of the same id merges next to the forwarder.
        val wordlist = SyncEntry(
            id = original.id,
            kind = EntryKind.WORDLIST,
            versions = listOf(signer.version(original.id, EntryKind.WORDLIST, stamp(), history(), wordlist(), parts())),
        )
        val both = EnvelopeMerger().merge(mapOf(original.key to original), envelope(wordlist), writer, sanitizer())
        assertEquals(mapOf(original.key to original, wordlist.key to wordlist), both.entries)
        // An envelope holds each kind of the id once, so neither is a duplicate.
        val sanitized = sanitizer().sanitize(envelope(original, wordlist), from = writer)
        assertEquals(emptyList(), sanitized.rejected)
    }

    @Test
    fun `histories of more writers than a roster holds devices are dropped`() {
        val others = (0..SanitizerLimits.MAX_HISTORY_WRITERS).map { stamp(now - 1, "w$it") }
        val history = VersionVector.of(others + stamp())
        assertEquals(RejectReason.TOO_MANY_WRITERS, reasonOf(entry(history = history)))
    }

    @Test
    fun `a history of more than 64 members is kept`() {
        val large = TestGroup(TestCrypto(32), size = 70, random = Random(32))
        val writer = large.keys[1]
        val stamps = large.keys.map { Hlc(now - 1, 0, it.id) } + Hlc(now, 0, writer.id)
        val history = VersionVector.of(stamps)
        val id = random.nextUuid()
        val stamp = Hlc(now, 0, writer.id)
        val version = large.entrySigner(writer)
            .version(id, kind, stamp, history, ForwarderBodies.forwarder("x"), keys.associateWith { stamp })
        val entry = SyncEntry(id = id, kind = kind, versions = listOf(version))
        val checked = Sanitizer(large.roster, large.groupId, large.crypto, now).check(entry)
        assertNull(checked.reason)
        assertEquals(70, checked.entry?.vv?.stamps?.size)
    }

    @Test
    fun `contradicting metadata is dropped`() {
        assertEquals(RejectReason.INCONSISTENT, reasonOf(SyncEntry(random.nextUuid(), kind)))
        // A history holds its own stamp, and nothing newer.
        assertEquals(RejectReason.INCONSISTENT, reasonOf(entry(history = VersionVector.EMPTY)))
        val newer = VersionVector.of(listOf(stamp(), stamp(now + 1, group.id(2))))
        assertEquals(RejectReason.INCONSISTENT, reasonOf(entry(history = newer)))
        // A part was last changed by an edit the version builds on, and not after it.
        assertEquals(RejectReason.INCONSISTENT, reasonOf(entry(parts = mapOf("/name" to stamp(now + 1)))))
        assertEquals(RejectReason.INCONSISTENT, reasonOf(entry(parts = mapOf("/name" to stamp(now - 1, group.id(2))))))
        // A delete saw itself.
        val entry = entry()
        assertEquals(RejectReason.INCONSISTENT, reasonOf(entry.deletedAt(stamp(now + 1), history = entry.vv)))
    }

    @Test
    fun `parts of a newer build are kept`() {
        val entry = entry(parts = keys.associateWith { stamp() } + ("/future" to stamp()))
        assertNull(reasonOf(entry))
    }

    @Test
    fun `no count of parts is too many`() {
        // The envelope budgets bound the parts; a kind of a newer build may name more than 64.
        val many = (0 until 100).associate { "/part$it" to stamp() }
        assertNull(reasonOf(entry(parts = keys.associateWith { stamp() } + many)))
    }

    @Test
    fun `every writer must have joined the group`() {
        val stranger = group.newKeys(random)
        val unknown = entry(stamp = stamp(node = stranger.id), signer = group.entrySigner(stranger))
        assertEquals(RejectReason.UNKNOWN_WRITER, reasonOf(unknown))
        // Nor can a member build on a stranger.
        val history = VersionVector.of(listOf(stamp(), stamp(now - 1, stranger.id)))
        assertEquals(RejectReason.UNKNOWN_WRITER, reasonOf(entry(history = history)))
        // A member does not write long before it joined.
        val joined = group.roster.devices.single { it.id == writer }.addedAt.wall
        val early = entry(stamp = stamp(joined - HlcClock.MAX_FUTURE_DRIFT_MS - 1))
        assertEquals(RejectReason.UNKNOWN_WRITER, reasonOf(early))
    }

    @Test
    fun `a device can only write as itself`() {
        // The third device signs a version that names the second one as its writer.
        val impostor = group.entrySigner(group.keys[2])
        assertEquals(RejectReason.FORGED, reasonOf(entry(signer = impostor)))
        // The body changed after it was signed.
        val tampered = entry().let { entry ->
            entry.copy(versions = entry.versions.map { it.copy(body = ForwarderBodies.forwarder("other")) })
        }
        assertEquals(RejectReason.FORGED, reasonOf(tampered))
        // The version moved to another entry.
        assertEquals(RejectReason.FORGED, reasonOf(entry().copy(id = random.nextUuid())))
        // A delete signed by another device.
        val deleted = entry().deletedAt(stamp(now + 1), signer = impostor)
        val checked = sanitizer().check(deleted)
        assertEquals(RejectReason.FORGED, checked.reason)
        assertEquals(emptyList<EntryDelete>(), checked.entry?.deletes)
    }

    @Test
    fun `the writes of a removed device still count`() {
        // A device that took them before it learned of the removal can not take them back,
        // so every device takes them.
        val roster = group.revoke(group.roster, group.keys[1], group.founder, stamp(now - 1_000, group.founder.id))
        assertNull(reasonOf(entry(), roster))
    }

    @Test
    fun `an envelope from an inactive device is dropped whole`() {
        val roster = group.revoke(group.roster, group.keys[1], group.founder, stamp(now - 1_000, group.founder.id))
        val old = entry(stamp = stamp(now - 2_000))
        val result = sanitizer(roster).sanitize(envelope(old), from = writer)
        assertEquals(emptyList(), result.accepted)
        assertEquals(listOf(RejectedEntry(old.id, RejectReason.INACTIVE_SENDER)), result.rejected)
    }

    @Test
    fun `the envelope merger reports changes and rejections`() {
        val kept = entry()
        val bad = entry(id = "bad")
        val result = EnvelopeMerger().merge(emptyMap(), envelope(kept, bad), writer, sanitizer())
        assertEquals(mapOf(kept.key to kept), result.entries)
        assertEquals(setOf(kept.key), result.changed)
        assertEquals(listOf(RejectedEntry("bad", RejectReason.INVALID_ID)), result.rejected)
        assertEquals(kept.stamp, result.newestStamp)
        val again = EnvelopeMerger().merge(result.entries, envelope(kept), writer, sanitizer())
        assertEquals(emptySet(), again.changed)
        assertNotNull(again.entries[kept.key])
        assertTrue(again.rejected.isEmpty())
    }

    @Test
    fun `an id the envelope holds twice is dropped with every copy`() {
        val id = random.nextUuid()
        val kept = entry()
        // Each copy fits; merged, they would not.
        val first = concurrent(id, 0 until 40)
        val second = concurrent(id, 40 until 80)
        val result = EnvelopeMerger().merge(emptyMap(), envelope(first, kept, second), writer, sanitizer())
        assertEquals(mapOf(kept.key to kept), result.entries)
        assertEquals(
            listOf(RejectedEntry(id, RejectReason.DUPLICATE_ID), RejectedEntry(id, RejectReason.DUPLICATE_ID)),
            result.rejected,
        )
    }

    @Test
    fun `a merge that would hold more versions and deletes than the limits is refused`() {
        val id = random.nextUuid()
        val key = EntryKey(kind, id)
        val local = EnvelopeMerger().merge(emptyMap(), envelope(concurrent(id, 0 until 40)), writer, sanitizer())
        assertEquals(40, local.entries.getValue(key).versions.size)
        // The incoming entry fits on its own.
        val incoming = concurrent(id, 40 until 70)
        assertNull(reasonOf(incoming))
        val result = EnvelopeMerger().merge(local.entries, envelope(incoming), writer, sanitizer())
        assertEquals(local.entries, result.entries)
        assertEquals(emptySet(), result.changed)
        assertEquals(emptyList(), result.accepted)
        assertNull(result.newestStamp)
        assertEquals(listOf(RejectedEntry(id, RejectReason.TOO_MANY_WRITERS)), result.rejected)
        // Up to the limit, the versions merge.
        val fits = EnvelopeMerger().merge(local.entries, envelope(concurrent(id, 40 until 64)), writer, sanitizer())
        assertEquals(SanitizerLimits.MAX_ELEMENTS, fits.entries.getValue(key).versions.size)
        assertTrue(fits.rejected.isEmpty())
    }

    @Test
    fun `every entry a merge keeps passes the sanitizer of a fresh device`() {
        val ids = List(3) { random.nextUuid() }
        var entries = emptyMap<EntryKey, SyncEntry>()
        repeat(60) {
            val id = ids.random(random)
            val start = random.nextInt(0, 100)
            val incoming = concurrent(id, start until start + random.nextInt(1, 40))
            entries = EnvelopeMerger().merge(entries, envelope(incoming), writer, sanitizer()).entries
            entries.values.forEach { entry ->
                assertEquals(entry, sanitizer().check(entry).entry)
            }
        }
        // The merges got to the limit.
        assertTrue(entries.values.any { it.versions.size > SanitizerLimits.MAX_ELEMENTS / 2 })
    }
}
