package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.crypto.signToBase64Url
import com.artemchep.keyguard.feature.p2psync.model.EntryDelete
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.SimDevice
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestDeviceKeys
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signed format of versions and deletes: what a newer build adds survives older builds
 * and verifies everywhere, and an unknown kind travels like a known one.
 */
class SignedEntryFormatTest {
    private val random = Random(41)
    private val group = TestGroup(TestCrypto(41), size = 3, random = random)
    private var time = 9_000_000L
    private val devices = group.simDevices { time++ }
    private val kind = EntryKind.EMAIL_FORWARDER

    /** [version] of the entry [id] with the field [name] a newer build added, signed again by [keys]. */
    private fun withField(
        version: EntryVersion,
        id: String,
        name: String,
        keys: TestDeviceKeys,
        entryKind: String = kind.wireName,
    ): EntryVersion {
        val unsigned = EntryVersion.fromRaw(JsonObject(version.raw - "sig" + (name to JsonPrimitive("future"))))
        val bytes = EntrySignatures.versionBytes(group.groupId, id, entryKind, unsigned)
        return unsigned.copy(sig = group.crypto.signToBase64Url(keys.signPrivate, bytes))
    }

    /** [delete] of the entry [id] with the field [name] a newer build added, signed again by [keys]. */
    private fun withField(delete: EntryDelete, id: String, name: String, keys: TestDeviceKeys): EntryDelete {
        val unsigned = EntryDelete.fromRaw(JsonObject(delete.raw - "sig" + (name to JsonPrimitive("future"))))
        val bytes = EntrySignatures.deleteBytes(group.groupId, id, kind.wireName, unsigned)
        return unsigned.copy(sig = group.crypto.signToBase64Url(keys.signPrivate, bytes))
    }

    private fun sanitizer() = Sanitizer(group.roster, group.groupId, group.crypto, time)

    @Test
    fun `a version with a field of a newer build verifies and survives every round trip`() {
        val (a, b, c) = devices
        val id = random.nextUuid()
        val original = a.edit(id, ForwarderBodies.forwarder("one"))
        val future = withField(original.versions.single(), id, "future", a.keys)
        val entry = original.copy(versions = listOf(future))
        assertNull(sanitizer().check(entry).reason)
        // The typed view ignores the field; the version keeps it.
        assertEquals(original.versions.single().body, future.body)
        assertEquals(JsonPrimitive("future"), future.raw["future"])

        // Storage, as the app stores versions.
        val serializer = ListSerializer(EntryVersion.serializer())
        val stored = P2pJson.decodeFromString(serializer, P2pJson.encodeToString(serializer, listOf(future)))
        assertEquals(listOf(future), stored)

        // The wire, through a device that relays it.
        val sent = a.envelope().copy(entries = listOf(entry))
        val decoded = assertNotNull(EnvelopeCodec.decodeOrNull(EnvelopeCodec.encode(sent), group.crypto::sha256))
        assertEquals(listOf(entry), decoded.envelope.entries)
        val merged = b.receive(a, decoded.envelope)
        assertEquals(emptyList(), merged.rejected)
        val relayed = c.receive(b)
        assertEquals(emptyList(), relayed.rejected)
        assertEquals(future, c.entryOf(id).versions.single())
        assertEquals("one", c.name(id))
    }

    @Test
    fun `a field of a newer build is signed`() {
        val (a) = devices
        val id = random.nextUuid()
        val original = a.edit(id, ForwarderBodies.forwarder("one"))
        val future = withField(original.versions.single(), id, "future", a.keys)
        val tampered = EntryVersion.fromRaw(JsonObject(future.raw + ("future" to JsonPrimitive("changed"))))
        assertEquals(RejectReason.FORGED, sanitizer().check(original.copy(versions = listOf(tampered))).reason)
    }

    @Test
    fun `a delete with a field of a newer build verifies and survives a round trip`() {
        val (a, b) = devices
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("one"))
        val deleted = a.delete(id)
        val future = withField(deleted.deletes.single(), id, "future", a.keys)
        val entry = deleted.copy(deletes = listOf(future))
        assertNull(sanitizer().check(entry).reason)
        val sent = a.envelope().copy(entries = listOf(entry))
        val decoded = assertNotNull(EnvelopeCodec.decodeOrNull(EnvelopeCodec.encode(sent), group.crypto::sha256))
        b.receive(a, decoded.envelope)
        assertEquals(future, b.entryOf(id).deletes.single())
        assertTrue(b.entryOf(id).deleted)
    }

    @Test
    fun `an unknown kind is checked, kept and relayed, but never materialized`() {
        val (a, b, c) = devices
        val id = random.nextUuid()
        val unknownKind = "card-reader"
        val stamp = Hlc(time, 0, a.id)
        val body = JsonObject(mapOf("number" to JsonPrimitive("1234")))
        val version = a.signer.version(id, unknownKind, stamp, VersionVector.of(listOf(stamp)), body, emptyMap())
        val entry = SyncEntry(id = id, kind = unknownKind, versions = listOf(version))
        assertNull(entry.knownKind)
        assertEquals(body, entry.body)

        val merged = b.receive(a, a.envelope().copy(entries = listOf(entry)))
        assertEquals(setOf(EntryKey(unknownKind, id)), merged.changed)
        assertNull(b.view[id])
        // Relayed to a device that never met the writer's session.
        c.receive(b)
        assertEquals(entry, c.entries[EntryKey(unknownKind, id)])
        assertNull(c.view[id])

        // Its signature covers the kind, as for a known one.
        val relabelled = entry.copy(kind = "other-kind")
        assertEquals(RejectReason.FORGED, sanitizer().check(relabelled).reason)
    }

    @Test
    fun `an unknown kind counts toward the same limits`() {
        val (a) = devices
        val id = random.nextUuid()
        val versions = (0..SanitizerLimits.MAX_ELEMENTS).map { index ->
            val stamp = Hlc(time - 1_000 + index, 0, a.id)
            val history = VersionVector.of(listOf(stamp, Hlc(time - 2_000 - index, 0, group.id(2))))
            a.signer.version(id, "card-reader", stamp, history, JsonObject(emptyMap()), emptyMap())
        }
        val entry = SyncEntry(id = id, kind = "card-reader", versions = versions)
        assertEquals(RejectReason.TOO_MANY_WRITERS, sanitizer().check(entry).reason)
        val large = JsonObject(mapOf("blob" to JsonPrimitive("x".repeat(SanitizerLimits.MAX_BODY_BYTES))))
        val stamp = Hlc(time, 0, a.id)
        val oversized = a.signer.version(id, "card-reader", stamp, VersionVector.of(listOf(stamp)), large, emptyMap())
        val reason = sanitizer().check(SyncEntry(id = id, kind = "card-reader", versions = listOf(oversized))).reason
        assertEquals(RejectReason.OVERSIZED_BODY, reason)
    }

    @Test
    fun `a version whose body holds a number without a canonical form can not be read`() {
        val (a) = devices
        val id = random.nextUuid()
        val version = a.edit(id, ForwarderBodies.forwarder("one")).versions.single()
        val body = JsonObject(version.body + ("count" to P2pJson.parseToJsonElement("1.5")))
        assertFailsWith<IllegalArgumentException> { EntryVersion.fromRaw(JsonObject(version.raw + ("body" to body))) }
        val float = JsonObject(version.raw + ("future" to P2pJson.parseToJsonElement("1e3")))
        assertFailsWith<IllegalArgumentException> { EntryVersion.fromRaw(float) }
        // A safe integer reads.
        val integer = JsonObject(version.raw + ("future" to P2pJson.parseToJsonElement("1000")))
        assertEquals(version.stamp, EntryVersion.fromRaw(integer).stamp)
    }

    @Test
    fun `a signature in another base64url form is refused`() {
        val (a) = devices
        val id = random.nextUuid()
        val entry = a.edit(id, ForwarderBodies.forwarder("one"))
        val version = entry.versions.single()
        val padded = version.copy(sig = version.sig + "=")
        assertEquals(RejectReason.FORGED, sanitizer().check(entry.copy(versions = listOf(padded))).reason)
        assertFalse(padded == version)
    }
}
