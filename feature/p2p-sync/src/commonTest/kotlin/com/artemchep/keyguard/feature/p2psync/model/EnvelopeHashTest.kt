package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.merge.EntryBudget
import com.artemchep.keyguard.feature.p2psync.merge.LocalEditPublisher
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.IdentityCompressor
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.transport.Compressor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EnvelopeHashTest {
    private val crypto = TestCrypto()
    private val group = TestGroup(crypto, size = 2)
    private val hasher: (ByteArray) -> ByteArray = crypto::sha256

    private val publisher = LocalEditPublisher()

    /** A new entry with the [body], written at the same time whatever the call. */
    private fun entry(id: String, name: String, body: JsonObject = ForwarderBodies.forwarder(name)) = publisher.publish(
        id = id,
        kind = EntryKind.EMAIL_FORWARDER,
        current = null,
        applied = null,
        local = body,
        clock = HlcClock(node = group.id(0), physicalNow = { 10L }, persist = {}),
        signer = group.entrySigner(group.keys[0]),
        budget = EntryBudget.of(emptyList()),
    )

    private fun envelope(vararg entries: SyncEntry) = Envelope(entries = entries.toList())

    private fun hashOf(envelope: Envelope): String = LocalEnvelope(group.roster, envelope, hasher).contentHash

    @Test
    fun `an envelope holds its format version and entries only`() {
        val a = entry("00000000-0000-0000-0000-000000000001", "one")
        val json = P2pJson.parseToJsonElement(EnvelopeCodec.encode(envelope(a)).decodeToString()) as JsonObject
        assertEquals(setOf("v", "entries"), json.keys)
        assertEquals(listOf("v", "entries"), Envelope.serializer().descriptor.elementNames.toList())
    }

    @Test
    fun `the sessions of every peer compress the envelope once`() {
        val local = LocalEnvelope(group.roster, envelope(entry("00000000-0000-0000-0000-000000000001", "one")), hasher)
        var compressed = 0
        val counting = object : Compressor by IdentityCompressor {
            override fun compress(bytes: ByteArray): ByteArray {
                compressed++
                return IdentityCompressor.compress(bytes)
            }
        }
        assertContentEquals(local.bytes, local.compressed(counting))
        assertContentEquals(local.bytes, local.compressed(counting))
        assertEquals(1, compressed)
        // Another compressor makes other bytes.
        local.compressed(IdentityCompressor)
        local.compressed(counting)
        assertEquals(2, compressed)
    }

    @Test
    fun `the hash is of the bytes as sent`() {
        val a = entry("00000000-0000-0000-0000-000000000001", "one")
        val local = LocalEnvelope(group.roster, envelope(a), hasher)
        assertEquals(P2pEncoding.hex(hasher(EnvelopeCodec.encode(envelope(a)))), local.contentHash)
        assertEquals(EnvelopeCodec.hashOf(local.bytes, hasher), local.contentHash)
        // The roster travels on its own and is no part of it.
        val other = LocalEnvelope(group.roster.copy(revoked = emptyList(), devices = emptyList()), envelope(a), hasher)
        assertEquals(local.contentHash, other.contentHash)
    }

    @Test
    fun `the receiver hashes the bytes it decoded, not a new encoding of them`() {
        val a = entry("00000000-0000-0000-0000-000000000001", "one")
        val canonical = EnvelopeCodec.encode(envelope(a))
        // The same envelope, with its fields in another order and white space.
        val entries = (P2pJson.parseToJsonElement(canonical.decodeToString()) as JsonObject).getValue("entries")
        val sent = """{ "v": 1, "entries": $entries }""".encodeToByteArray()
        val decoded = assertNotNull(EnvelopeCodec.decodeOrNull(sent, hasher))
        assertEquals(envelope(a), decoded.envelope)
        assertEquals(EnvelopeCodec.hashOf(sent, hasher), decoded.contentHash)
        assertNotEquals(EnvelopeCodec.hashOf(canonical, hasher), decoded.contentHash)
    }

    @Test
    fun `the hash ignores the order of entries and keys`() {
        val a = entry("00000000-0000-0000-0000-000000000001", "one")
        val b = entry("00000000-0000-0000-0000-000000000002", "two")
        val body = ForwarderBodies.forwarder("one", "anonaddy", "apiKey" to "key", "domain" to "example.com")
        fun JsonObject.reversed() = JsonObject(entries.reversed().associate { it.toPair() })
        val reversed = JsonObject(
            body.reversed().mapValues { (_, value) -> (value as? JsonObject)?.reversed() ?: value },
        )
        val sorted = entry("00000000-0000-0000-0000-000000000001", "one", body)
        val reversedKeys = entry("00000000-0000-0000-0000-000000000001", "one", reversed)
        assertEquals(hashOf(envelope(a, b)), hashOf(envelope(b, a)))
        assertEquals(hashOf(envelope(sorted)), hashOf(envelope(reversedKeys)))
    }

    @Test
    fun `the hash changes with the content`() {
        val a = entry("00000000-0000-0000-0000-000000000001", "one")
        val renamed = entry("00000000-0000-0000-0000-000000000001", "uno")
        assertNotEquals(hashOf(envelope(a)), hashOf(envelope(renamed)))
    }

    @Test
    fun `the wire form round trips`() {
        val a = entry("00000000-0000-0000-0000-000000000001", "one")
        val original = envelope(a)
        val decoded = assertNotNull(EnvelopeCodec.decodeOrNull(EnvelopeCodec.encode(original), hasher))
        assertEquals(original.normalized(), decoded.envelope)
        assertEquals(hashOf(original), decoded.contentHash)
    }

    @Test
    fun `entries of a broken shape are skipped not the envelope, and unknown kinds are kept`() {
        val a = entry("00000000-0000-0000-0000-000000000001", "one")
        val json = P2pJson.encodeToJsonElement(Envelope.serializer(), envelope(a)) as JsonObject
        val entries = json.getValue("entries") as JsonArray
        val future = JsonObject((entries[0] as JsonObject) + ("kind" to P2pJson.parseToJsonElement("\"card-reader\"")))
        val broken = JsonObject(mapOf("id" to P2pJson.parseToJsonElement("\"x\"")))
        val patched = JsonObject(json + ("entries" to JsonArray(entries + future + broken)))
        val sent = CanonicalJson.encodeToByteArray(patched)
        val decoded = assertNotNull(EnvelopeCodec.decodeOrNull(sent, hasher))
        assertEquals(listOf(a, a.copy(kind = "card-reader")), decoded.envelope.entries)
        assertNull(decoded.envelope.entries.last().knownKind)
        // The hash still names the envelope as sent, so the sender recognizes its ACK.
        assertEquals(EnvelopeCodec.hashOf(sent, hasher), decoded.contentHash)
        assertNotEquals(hashOf(decoded.envelope), decoded.contentHash)
    }

    @Test
    fun `an envelope of another format version or not an envelope at all is refused`() {
        val a = entry("00000000-0000-0000-0000-000000000001", "one")
        val newer = EnvelopeCodec.encode(envelope(a).copy(v = Envelope.VERSION + 1))
        assertNull(EnvelopeCodec.decodeOrNull(newer, hasher))
        assertEquals(EnvelopeDecoding.UnsupportedVersion(Envelope.VERSION + 1), EnvelopeCodec.decode(newer, hasher))
        val older = EnvelopeCodec.encode(envelope(a).copy(v = Envelope.VERSION - 1))
        assertEquals(EnvelopeDecoding.UnsupportedVersion(Envelope.VERSION - 1), EnvelopeCodec.decode(older, hasher))
        assertNull(EnvelopeCodec.decodeOrNull("not json".encodeToByteArray(), hasher))
        assertEquals(EnvelopeDecoding.Malformed, EnvelopeCodec.decode("[]".encodeToByteArray(), hasher))
    }

    @Test
    fun `a local envelope is hashed and encoded as the envelope itself is`() {
        val original = envelope(
            entry("00000000-0000-0000-0000-000000000002", "two"),
            entry("00000000-0000-0000-0000-000000000001", "one"),
        )
        val local = LocalEnvelope(group.roster, original, hasher)
        assertEquals(EnvelopeCodec.hashOf(EnvelopeCodec.encode(original), hasher), local.contentHash)
        assertContentEquals(EnvelopeCodec.encode(original), local.bytes)
    }

    @Test
    fun `an envelope nested too deep or with a number without a canonical form is refused`() {
        val a = entry("00000000-0000-0000-0000-000000000001", "unique-name")
        val text = EnvelopeCodec.encode(envelope(a)).decodeToString()
        fun withName(value: String) = text.replace("\"unique-name\"", value).encodeToByteArray()
        val depth = EnvelopeCodec.MAX_DEPTH
        assertNotNull(EnvelopeCodec.decodeOrNull(withName("[[1]]"), hasher))
        assertNull(EnvelopeCodec.decodeOrNull(withName("[".repeat(depth) + "]".repeat(depth)), hasher))
        assertNull(EnvelopeCodec.decodeOrNull(withName("[".repeat(100_000) + "]".repeat(100_000)), hasher))
        assertNotNull(EnvelopeCodec.decodeOrNull(withName("9007199254740991"), hasher))
        assertNotNull(EnvelopeCodec.decodeOrNull(withName("-9007199254740991"), hasher))
        // Floats, exponents, other forms of integers and integers past 2^53 - 1.
        listOf("1.5", "1.0", "1e2", "-0", "01", "9007199254740992").forEach { number ->
            assertEquals(EnvelopeDecoding.Malformed, EnvelopeCodec.decode(withName(number), hasher), number)
        }
        assertNull(EnvelopeCodec.decodeOrNull(withName("foo"), hasher))
        // Brackets inside strings do not count.
        assertNotNull(EnvelopeCodec.decodeOrNull(withName("\"" + "[\\\"{".repeat(100) + "\""), hasher))
    }
}
