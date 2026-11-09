package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.P2pJson
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalJsonTest {
    private fun canonical(json: String): String = CanonicalJson.encode(P2pJson.parseToJsonElement(json))

    @Test
    fun `object keys are sorted and whitespace is dropped`() {
        assertEquals(
            """{"a":[3,{"x":1,"y":2}],"b":true,"c":null}""",
            canonical("""{ "c": null, "b": true, "a": [3, { "y": 2, "x": 1 }] }"""),
        )
    }

    @Test
    fun `keys sort by UTF-16 code units`() {
        assertEquals("""{"A":1,"a":2,"é":3,"😀":4}""", canonical("""{"😀":4,"é":3,"a":2,"A":1}"""))
    }

    @Test
    fun `integers within the safe range have one textual form`() {
        listOf("0", "-1", "100", "9007199254740991", "-9007199254740991").forEach { input ->
            assertEquals(input, canonical(input), "input $input")
            assertTrue(CanonicalJson.isCanonical(P2pJson.parseToJsonElement(input)))
        }
    }

    @Test
    fun `other numbers have no canonical form`() {
        val invalid = listOf(
            "-0",
            "01",
            "1.0",
            "1.5",
            "1e2",
            "1E+2",
            "0.001",
            "9007199254740992",
            "-9007199254740992",
            "123456789012345678901234",
        )
        invalid.forEach { input ->
            assertFailsWith<IllegalArgumentException>("input $input") { canonical(input) }
            assertFalse(CanonicalJson.isCanonical(P2pJson.parseToJsonElement("""{"a":[$input]}""")), input)
        }
    }

    @Test
    fun `strings use minimal escaping and keep unicode as is`() {
        assertEquals(
            "\"quote\\\" backslash\\\\ tab\\t newline\\n bell\\u0007 é 😀 /\"",
            canonical("\"quote\\\" backslash\\\\ tab\\t newline\\n bell\\u0007 \\u00e9 😀 \\/\""),
        )
        assertEquals("\"\"", canonical("\"\""))
    }

    @Test
    fun `equal documents encode to equal bytes`() {
        val a = """{"name":"x","login":{"password":"p","username":"u"},"n":1}"""
        val b = """{"login":{"username":"u","password":"p"},"n":1,"name":"x"}"""
        assertEquals(canonical(a), canonical(b))
    }

    @Test
    fun `invalid literals are refused`() {
        assertFailsWith<IllegalArgumentException> { canonical("NaN") }
        // An unquoted literal, which the parser reads as a number.
        assertFailsWith<IllegalArgumentException> { canonical("""{"name":foo}""") }
    }

    @Test
    fun `unpaired surrogates are escaped and pairs are kept`() {
        assertEquals("\"a\\ud800b\"", CanonicalJson.encode(JsonPrimitive("a\uD800b")))
        assertEquals("\"\\udc00\\ud800\"", CanonicalJson.encode(JsonPrimitive("\uDC00\uD800")))
        assertEquals("\"\\ud800😀\"", CanonicalJson.encode(JsonPrimitive("\uD800😀")))
        assertEquals("\"😀\"", CanonicalJson.encode(JsonPrimitive("😀")))
    }

    @Test
    fun `a string with an unpaired surrogate has no canonical form`() {
        // A store or a message would replace it, and the signature would no longer verify.
        listOf(
            """{"a":"\ud800"}""",
            """{"\udc00":1}""",
            """{"a":[{"b":"x\ud800y"}]}""",
        ).forEach { json ->
            assertFalse(CanonicalJson.isCanonical(P2pJson.parseToJsonElement(json)), json)
        }
        assertTrue(CanonicalJson.isCanonical(P2pJson.parseToJsonElement("""{"a":"\ud83d\ude00"}""")))
    }
}
