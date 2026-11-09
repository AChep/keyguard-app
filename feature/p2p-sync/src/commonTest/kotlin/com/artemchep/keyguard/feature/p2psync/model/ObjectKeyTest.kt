package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.merge.Sanitizer
import com.artemchep.keyguard.feature.p2psync.testing.SimDevice
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ObjectKeyTest {
    private val crypto = TestCrypto(5)
    private val bytes = Random(5).nextBytes(ObjectKey.KEY_BYTES)

    private fun body(key: Any?, covers: Any?): JsonObject = JsonObject(
        buildMap {
            if (key != null) put("key", json(key))
            if (covers != null) put("covers", json(covers))
        },
    )

    private fun json(value: Any): JsonElement = when (value) {
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { json(requireNotNull(it)) })
        else -> error("Unexpected $value")
    }

    @Test
    fun `a key survives its body`() {
        val key = ObjectKey(bytes, covers = listOf("a", "b"))
        assertEquals(key, ObjectKey.fromBody(key.toBody()))
        assertEquals(body(P2pEncoding.base64Url(bytes), listOf("a", "b")), key.toBody())
    }

    @Test
    fun `fields of a newer build are ignored`() {
        val key = ObjectKey(bytes, covers = emptyList())
        val newer = JsonObject(key.toBody() + ("extra" to JsonPrimitive(1)))
        assertEquals(key, ObjectKey.fromBody(newer))
    }

    @Test
    fun `any other body holds no key`() {
        val key = P2pEncoding.base64Url(bytes)
        val bodies = listOf(
            body(key = null, covers = emptyList<String>()),
            body(key = key, covers = null),
            body(key = 1, covers = emptyList<String>()),
            body(key = P2pEncoding.base64Url(bytes.copyOf(ObjectKey.KEY_BYTES - 1)), covers = emptyList<String>()),
            body(key = P2pEncoding.base64Url(bytes + 0.toByte()), covers = emptyList<String>()),
            body(key = "$key=", covers = emptyList<String>()),
            body(key = key, covers = "a"),
            body(key = key, covers = listOf("b", "a")),
            body(key = key, covers = listOf("a", "a")),
            body(key = key, covers = listOf(1)),
            body(key = key, covers = List(Roster.MAX_DEVICES + 1) { it.toString().padStart(4, '0') }),
        )
        bodies.forEach { body ->
            assertNull(ObjectKey.fromBody(body), "$body")
        }
        val most = List(Roster.MAX_DEVICES) { it.toString().padStart(4, '0') }
        assertEquals(most, ObjectKey.fromBody(body(key = key, covers = most))?.covers)
    }

    @Test
    fun `the id of a key is the hex of the first 8 bytes of its SHA-256`() {
        val key = ObjectKey(bytes, covers = emptyList())
        assertEquals(P2pEncoding.hex(crypto.sha256(bytes).copyOf(8)), key.keyId(crypto::sha256))
        assertEquals(16, key.keyId(crypto::sha256).length)
    }

    @Test
    fun `the entry of the key is a valid entry of every group`() {
        val group = TestGroup(crypto)
        val device = SimDevice(group.founder.id, group) { 7_000_000L }
        val key = ObjectKey(bytes, covers = emptyList())
        val entry = device.edit(ObjectKey.ENTRY_ID, key.toBody(), EntryKind.OBJECT_KEY)
        val sanitizer = Sanitizer(group.roster, group.groupId, crypto, 7_000_000L)
        assertNull(sanitizer.check(entry).reason)
        assertEquals(key, ObjectKey.fromEntry(entry))
        // A deleted entry, or another entry, holds no key.
        assertNull(ObjectKey.fromEntry(device.delete(ObjectKey.ENTRY_ID, EntryKind.OBJECT_KEY)))
        val other = device.edit(ObjectKey.ENTRY_ID, key.toBody(), EntryKind.URL_OVERRIDE)
        assertNotNull(other.body)
        assertNull(ObjectKey.fromEntry(other))
    }
}
