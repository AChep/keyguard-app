package com.artemchep.keyguard.feature.p2psync.mailbox

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.merge.EntryBudget
import com.artemchep.keyguard.feature.p2psync.merge.LocalEditPublisher
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailboxCodecTest {
    private val crypto = TestCrypto()
    private val group = TestGroup(crypto, size = 2)
    private val hasher: (ByteArray) -> ByteArray = crypto::sha256

    private val stateId = "a".repeat(MailboxLimits.ITEM_ID_LENGTH)
    private val address = MailboxAddress(item = stateId, from = group.id(0), to = group.id(1))

    private val now = 1_700_000_000_000L
    private val expiresAt = now + MailboxLimits.TTL_SECONDS * 1_000L

    private val blob = "the words of a wordlist".encodeToByteArray()
    private val blobHash = BlobExchange.hashOf(crypto, blob)
    private val otherHash = BlobExchange.hashOf(crypto, "other".encodeToByteArray())

    private fun entry(id: String, name: String): SyncEntry = LocalEditPublisher().publish(
        id = id,
        kind = EntryKind.EMAIL_FORWARDER,
        current = null,
        applied = null,
        local = ForwarderBodies.forwarder(name),
        clock = HlcClock(node = group.id(0), physicalNow = { 10L }, persist = {}),
        signer = group.entrySigner(group.keys[0]),
        budget = EntryBudget.of(emptyList()),
    )

    private val local = LocalEnvelope(
        roster = group.roster,
        envelope = Envelope(entries = listOf(entry("00000000-0000-0000-0000-000000000001", "one"))),
        hasher = hasher,
    )

    private fun draft(
        envelope: LocalEnvelope? = local,
        missing: List<String> = listOf(blobHash),
    ) = MailboxStateDraft(address, group.roster, envelope, missing)

    private fun decode(
        bytes: ByteArray,
        expected: MailboxAddress = address,
        at: Long = now,
        maxEnvelopeSize: Int = 2 * 1024 * 1024,
    ) = MailboxCodec.decodeState(bytes, expected, at, hasher, maxEnvelopeSize)

    private fun rejection(opening: MailboxOpening<*>): MailboxRejection =
        assertIs<MailboxOpening.Rejected>(opening).reason

    /** Returns the item [bytes] with the fields of [change] in place of its own, canonical again. */
    private fun edited(bytes: ByteArray, change: Map<String, Any?>): ByteArray {
        val json = P2pJson.parseToJsonElement(bytes.decodeToString()) as JsonObject
        val fields = json.toMutableMap()
        change.forEach { (key, value) ->
            when (value) {
                null -> fields.remove(key)
                is String -> fields[key] = JsonPrimitive(value)
                is Number -> fields[key] = JsonPrimitive(value)
                is JsonArray -> fields[key] = value
                else -> error("Unexpected $value")
            }
        }
        return CanonicalJson.encodeToByteArray(JsonObject(fields))
    }

    @Test
    fun `a state item round trips with the content hash of the sender`() {
        val bytes = draft().encode(expiresAt = expiresAt)
        val state = assertIs<MailboxOpening.Valid<MailboxState>>(decode(bytes)).item
        assertEquals(address, state.address)
        assertEquals(expiresAt, state.expiresAt)
        assertEquals(group.roster.normalized(), state.roster)
        val envelope = assertNotNull(state.envelope)
        // The recipient hashes the envelope as the sender's sessions do.
        assertEquals(local.contentHash, envelope.contentHash)
        assertEquals(local.envelope.normalized(), envelope.envelope.normalized())
        assertEquals(listOf(blobHash), state.missing)
        // A state item says nothing of when it was made.
        val json = P2pJson.parseToJsonElement(bytes.decodeToString()) as JsonObject
        assertFalse("createdAt" in json)
    }

    @Test
    fun `a roster only state item has no envelope`() {
        val bytes = draft(envelope = null, missing = emptyList()).encode(expiresAt)
        val state = assertIs<MailboxOpening.Valid<MailboxState>>(decode(bytes)).item
        assertNull(state.envelope)
        assertEquals(group.roster.normalized(), state.roster)
    }

    @Test
    fun `an envelope of another format leaves the roster readable`() {
        val bytes = draft().encode(expiresAt)
        val json = P2pJson.parseToJsonElement(bytes.decodeToString()) as JsonObject
        val envelope = JsonObject(mapOf("v" to JsonPrimitive(2), "entries" to JsonArray(emptyList())))
        val sent = CanonicalJson.encodeToByteArray(JsonObject(json + ("envelope" to envelope)))
        val state = assertIs<MailboxOpening.Valid<MailboxState>>(decode(sent)).item
        assertNull(state.envelope)
        assertEquals(group.roster.normalized(), state.roster)
        assertEquals(listOf(blobHash), state.missing)
    }

    @Test
    fun `the tag covers everything but the expiry`() {
        val first = draft()
        assertContentEquals(first.tagContent, draft().tagContent)
        assertFalse(first.encode(expiresAt).contentEquals(first.encode(expiresAt + 1)))
        // The missing blobs count, in any order.
        assertFalse(first.tagContent.contentEquals(draft(missing = emptyList()).tagContent))
        assertContentEquals(
            draft(missing = listOf(blobHash, otherHash)).tagContent,
            draft(missing = listOf(otherHash, blobHash)).tagContent,
        )
        assertFalse(first.tagContent.contentEquals(draft(envelope = null).tagContent))
    }

    @Test
    fun `an item moved to another id direction or kind is refused`() {
        val bytes = draft().encode(expiresAt)
        assertEquals(MailboxRejection.WRONG_ITEM, rejection(decode(bytes, address.copy(item = "b".repeat(64)))))
        // The reverse direction: the same pair, the other way round.
        val reversed = MailboxAddress(item = stateId, from = address.to, to = address.from)
        assertEquals(MailboxRejection.WRONG_SENDER, rejection(decode(bytes, reversed)))
        assertEquals(MailboxRejection.WRONG_RECIPIENT, rejection(decode(bytes, address.copy(to = "someone"))))
        assertEquals(MailboxRejection.WRONG_ITEM, rejection(decode(edited(bytes, mapOf("kind" to "blob")))))
        // A blob item is no state item, and the other way round.
        val blobItem = MailboxCodec.encodeBlob(blob)
        assertEquals(MailboxRejection.MALFORMED, rejection(decode(blobItem)))
        assertEquals(
            MailboxRejection.UNSUPPORTED_VERSION,
            rejection(MailboxCodec.decodeBlob(bytes, blobHash, hasher, maxBlobSize = 1024)),
        )
    }

    @Test
    fun `an expired item is refused whatever the clock of its sender`() {
        val bytes = draft().encode(expiresAt)
        assertIs<MailboxOpening.Valid<*>>(decode(bytes, at = expiresAt))
        assertEquals(MailboxRejection.EXPIRED, rejection(decode(bytes, at = expiresAt + 1)))
        // An item counts however far ahead the clock of its sender ran.
        assertIs<MailboxOpening.Valid<*>>(decode(bytes, at = now - 365L * 24L * 60L * 60L * 1_000L))
    }

    @Test
    fun `a malformed or tampered state item is refused`() {
        val bytes = draft().encode(expiresAt)
        assertEquals(MailboxRejection.MALFORMED, rejection(decode("{}".encodeToByteArray())))
        assertEquals(MailboxRejection.MALFORMED, rejection(decode("not json".encodeToByteArray())))
        assertEquals(MailboxRejection.UNSUPPORTED_VERSION, rejection(decode(edited(bytes, mapOf("v" to 2)))))
        assertEquals(MailboxRejection.MALFORMED, rejection(decode(edited(bytes, mapOf("expiresAt" to null)))))
        assertEquals(MailboxRejection.MALFORMED, rejection(decode(edited(bytes, mapOf("roster" to null)))))
        assertEquals(MailboxRejection.MALFORMED, rejection(decode(edited(bytes, mapOf("roster" to "roster")))))
        // A number without a canonical form.
        val float = bytes.decodeToString()
            .replaceFirst("\"expiresAt\":$expiresAt", "\"expiresAt\":$expiresAt.0")
            .encodeToByteArray()
        assertEquals(MailboxRejection.MALFORMED, rejection(decode(float)))
        assertEquals(MailboxRejection.MALFORMED, rejection(decode(edited(bytes, mapOf("missing" to null)))))
        assertEquals(MailboxRejection.MALFORMED, rejection(decode(edited(bytes, mapOf("missing" to blobHash)))))
    }

    @Test
    fun `a reader takes the first missing blobs and ignores the rest`() {
        val bytes = draft().encode(expiresAt)
        val hashes = List(MailboxLimits.MAX_MISSING + 2) { index ->
            BlobExchange.hashOf(crypto, "blob $index".encodeToByteArray())
        }
        // Every blob id is a lowercase base32 SHA-256: the other items are skipped, as are repeats.
        val skipped = listOf(JsonPrimitive(blobHash.uppercase()), JsonPrimitive("abc"), JsonPrimitive(1))
        val items = skipped + JsonPrimitive(hashes.first()) + hashes.map(::JsonPrimitive)
        val sent = edited(bytes, mapOf("missing" to JsonArray(items)))

        val state = assertIs<MailboxOpening.Valid<MailboxState>>(decode(sent)).item
        assertEquals(hashes.take(MailboxLimits.MAX_MISSING), state.missing)
        // The roster and the envelope still count.
        assertEquals(group.roster.normalized(), state.roster)
        assertEquals(local.contentHash, assertNotNull(state.envelope).contentHash)
    }

    @Test
    fun `a state item past the limits is refused`() {
        val bytes = draft().encode(expiresAt)
        // An envelope larger than a session takes counts as none: the roster still counts.
        val small = assertIs<MailboxOpening.Valid<MailboxState>>(decode(bytes, maxEnvelopeSize = 16)).item
        assertNull(small.envelope)
        assertEquals(group.roster.normalized(), small.roster)
        assertFailsWith<IllegalArgumentException> {
            draft(missing = List(MailboxLimits.MAX_MISSING + 1) { otherHash })
        }
        // The most missing blobs fit.
        val most = List(MailboxLimits.MAX_MISSING) { index ->
            BlobExchange.hashOf(crypto, "blob $index".encodeToByteArray())
        }
        val full = assertIs<MailboxOpening.Valid<MailboxState>>(decode(draft(missing = most).encode(expiresAt)))
        assertEquals(most.sorted(), full.item.missing)
        // Nesting past the limits is never parsed.
        val deep = "[".repeat(64) + "]".repeat(64)
        val nested = bytes.decodeToString().replaceFirst("\"missing\":[", "\"missing\":[$deep,").encodeToByteArray()
        assertEquals(MailboxRejection.TOO_LARGE, rejection(decode(nested)))
        val oversized = ByteArray(MailboxCodec.maxStateSize(1024) + 1)
        assertEquals(MailboxRejection.TOO_LARGE, rejection(decode(oversized, maxEnvelopeSize = 1024)))
        // A draft knows whether its envelope fits a session.
        assertTrue(draft().fits(maxEnvelopeSize = 2 * 1024 * 1024))
        assertFalse(draft().fits(maxEnvelopeSize = 16))
        assertTrue(draft(envelope = null).fits(maxEnvelopeSize = 16))
    }

    @Test
    fun `a blob item round trips and must hold the blob asked for`() {
        val bytes = MailboxCodec.encodeBlob(blob)

        fun open(
            item: ByteArray = bytes,
            hash: String = blobHash,
            maxBlobSize: Long = 1024,
        ) = MailboxCodec.decodeBlob(item, hash, hasher, maxBlobSize)

        assertContentEquals(blob, assertIs<MailboxOpening.Valid<ByteArray>>(open()).item)
        // A version byte, then the blob: nothing of which blob it is, who left it, or when.
        assertEquals(MailboxLimits.VERSION, bytes[0].toInt())
        assertContentEquals(blob, bytes.copyOfRange(1, bytes.size))
        assertEquals(blob.size + 1L, MailboxCodec.maxBlobItemSize(blob.size.toLong()))
        // Another blob than the one asked for, or bytes that do not match it.
        assertEquals(MailboxRejection.WRONG_HASH, rejection(open(hash = otherHash)))
        val swapped = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(MailboxRejection.WRONG_HASH, rejection(open(item = swapped)))
        assertEquals(MailboxRejection.WRONG_HASH, rejection(open(item = bytes.copyOf(10))))
        assertEquals(MailboxRejection.TOO_LARGE, rejection(open(maxBlobSize = blob.size - 1L)))
        // The empty blob is a blob; no version byte is no item.
        val empty = MailboxCodec.encodeBlob(ByteArray(0))
        val emptyHash = BlobExchange.hashOf(crypto, ByteArray(0))
        assertIs<MailboxOpening.Valid<ByteArray>>(open(item = empty, hash = emptyHash))
        assertEquals(MailboxRejection.MALFORMED, rejection(open(item = ByteArray(0))))
    }

    @Test
    fun `a blob item of another version is refused as such`() {
        listOf(0, MailboxLimits.VERSION + 1, 0xFF).forEach { version ->
            val item = MailboxCodec.encodeBlob(blob).also { it[0] = version.toByte() }
            val opening = MailboxCodec.decodeBlob(item, blobHash, hasher, maxBlobSize = 1024)
            assertEquals(MailboxRejection.UNSUPPORTED_VERSION, rejection(opening), "version $version")
        }
    }

    @Test
    fun `blob ids are lowercase base32 SHA-256 digests`() {
        assertTrue(BlobExchange.isBlobId(blobHash))
        assertFalse(BlobExchange.isBlobId(blobHash.uppercase()))
        assertFalse(BlobExchange.isBlobId(blobHash.dropLast(1)))
        assertFalse(BlobExchange.isBlobId(P2pEncoding.base32(ByteArray(31))))
    }
}
