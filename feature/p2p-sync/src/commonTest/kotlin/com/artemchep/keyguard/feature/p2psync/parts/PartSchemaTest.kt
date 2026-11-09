package com.artemchep.keyguard.feature.p2psync.parts

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartSchemaTest {
    private fun json(text: String): JsonObject = P2pJson.parseToJsonElement(text).jsonObject

    @Test
    fun `forwarders have a name part and a config part`() {
        val schema = KeyguardPartSchemas.emailForwarder
        assertEquals(listOf("/name", "/data"), schema.parts.map { it.key })
        assertEquals(KeyguardPartSchemas.EMAIL_FORWARDER_CONFIG_PART, "/data")
        assertEquals(listOf("/type", "/data"), schema.part("/data")?.paths?.map { it.toString() })
        assertEquals(schema, EntryKind.EMAIL_FORWARDER.schema)
    }

    @Test
    fun `wordlists have a name part and a words part`() {
        val schema = KeyguardPartSchemas.wordlist
        assertEquals(listOf("/name", "/words"), schema.parts.map { it.key })
        assertEquals(schema, EntryKind.WORDLIST.schema)
    }

    @Test
    fun `a multi-path part reads and writes all its paths`() {
        val spec = KeyguardPartSchemas.emailForwarder.part("/data")!!
        val body = json("""{"name":"n","type":"t","data":{"apiKey":"k"}}""")
        val value = PartAccess.get(body, spec)
        assertEquals(json("""{"/type":"t","/data":{"apiKey":"k"}}"""), value)
        val cleared = PartAccess.set(body, spec, null)
        assertEquals(json("""{"name":"n"}"""), cleared)
        assertEquals(body, PartAccess.set(cleared, spec, value))
    }

    @Test
    fun `removing the last field removes its empty parents`() {
        val spec = PartSpec.of("/data/apiKey")
        val body = json("""{"name":"n","data":{"apiKey":"k"}}""")
        assertEquals(json("""{"name":"n"}"""), PartAccess.set(body, spec, null))
        assertEquals(body, PartAccess.set(json("""{"name":"n"}"""), spec, PartAccess.get(body, spec)))
        assertNull(PartAccess.get(json("""{"data":null}"""), spec))
        assertNull(PartAccess.get(json("""{"data":{"apiKey":null}}"""), spec))
    }

    @Test
    fun `the remainder keeps unknown fields only`() {
        val body = json("""{"name":"n","type":"t","data":{"apiKey":"k"},"extra":"e","nested":{"x":[]}}""")
        assertEquals(
            json("""{"extra":"e","nested":{"x":[]}}"""),
            PartAccess.remainder(body, KeyguardPartSchemas.emailForwarder),
        )
    }

    @Test
    fun `composing twice equals composing once`() {
        val schema = KeyguardPartSchemas.emailForwarder
        val base = json("""{"name":"a","x":1}""")
        val first = mapOf("/type" to P2pJson.parseToJsonElement("\"u\""))
        val second = mapOf("/name" to P2pJson.parseToJsonElement("\"b\""))
        assertEquals(
            PartAccess.compose(base, schema, second),
            PartAccess.compose(PartAccess.compose(base, schema, first), schema, second),
        )
    }

    @Test
    fun `json pointers escape their segments`() {
        val pointer = JsonPointer.parse("/a~1b/c~0d")
        assertEquals(listOf("a/b", "c~d"), pointer.segments)
        assertEquals("/a~1b/c~0d", pointer.toString())
        assertFailsWith<IllegalArgumentException> { JsonPointer.parse("name") }
        assertFailsWith<IllegalArgumentException> { PartSchema(listOf(PartSpec.of("/a"), PartSpec.of("/a"))) }
    }
}
