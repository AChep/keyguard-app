package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentLimits
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.test.createSecret
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CipherMergeEnvironmentTest {
    @Test
    fun `merge keeps every name in first seen order and preserves case`() {
        val first = payload(literal("B", "b"), literal("A", "a"))
        val second = payload(literal("A", "a"), literal("a", "lowercase"), literal("C", "c"))

        val merged = assertIs<EnvironmentPayload.Decoded>(merge(first, second))

        assertEquals(
            listOf(literal("B", "b"), literal("A", "a"), literal("a", "lowercase"), literal("C", "c")),
            merged.definition.variables,
        )
        assertTrue(merged.lossless)
        assertEquals(merged, EnvironmentFormat.decode(merged.document))
    }

    @Test
    fun `each variable chooses its own majority with input order breaking ties`() {
        val first = payload(literal("A", "first"), literal("B", "majority"), literal("TIE", "first"))
        val second = payload(literal("A", "majority"), literal("B", "second"), literal("TIE", "second"))
        val third = payload(literal("A", "majority"), literal("B", "majority"), literal("TIE", "third"))

        val merged = assertIs<EnvironmentPayload.Decoded>(merge(first, second, third))

        assertEquals(
            listOf(literal("A", "majority"), literal("B", "majority"), literal("TIE", "first")),
            merged.definition.variables,
        )
    }

    @Test
    fun `absence and empty definitions do not vote against a variable`() {
        val populated = payload(literal("TOKEN", "value"))

        assertSame(populated, merge(null, payload(), populated, payload(), null))
        assertNull(merge(null, null))
        val empty = payload()
        assertSame(empty, merge(null, empty, payload()))
    }

    @Test
    fun `unset and empty literals are distinct votes`() {
        val unset = EnvironmentVariable("TOKEN", EnvironmentValueSource.Unset)
        val empty = literal("TOKEN", "")

        assertEquals(payload(unset), merge(payload(unset), payload(empty)))
        assertEquals(payload(empty), merge(payload(unset), payload(empty), payload(empty)))
        assertEquals(payload(unset), merge(payload(empty), payload(unset), payload(unset)))
    }

    @Test
    fun `references vote as complete sources without resolution or field mixing`() {
        val password = EnvironmentVariable(
            "TOKEN",
            EnvironmentValueSource.Reference(FIRST_CIPHER_ID, EnvironmentFieldReference.Password),
        )
        val custom = EnvironmentVariable(
            "TOKEN",
            EnvironmentValueSource.Reference(FIRST_CIPHER_ID, EnvironmentFieldReference.Custom("secret")),
        )
        val other = EnvironmentVariable(
            "TOKEN",
            EnvironmentValueSource.Reference(SECOND_CIPHER_ID, EnvironmentFieldReference.Custom("secret")),
        )

        assertEquals(payload(password), merge(payload(password), payload(custom), payload(other)))
        assertEquals(payload(custom), merge(payload(password), payload(custom), payload(custom)))
        assertEquals(payload(password), merge(payload(literal("TOKEN", "value")), payload(password), payload(password)))
    }

    @Test
    fun `unchanged definitions retain their original document`() {
        val document = """{ "variables": [{"source":{"value":"value","kind":"literal"},""" +
            """"name":"TOKEN"}], "version": 1 }"""
        val original = requireNotNull(EnvironmentFormat.decode(document))

        assertSame(original, merge(original))
        assertSame(original, merge(original, payload(literal("TOKEN", "value"))))
    }

    @Test
    fun `unsupported payloads use whole payload majority and preserve the raw document`() {
        val unsupported = EnvironmentPayload.Unsupported(
            document = """{"version":2,"future":"raw secret"}""",
        )
        val decoded = payload(literal("TOKEN", "value"))

        assertSame(unsupported, merge(unsupported, decoded))
        assertSame(decoded, merge(decoded, unsupported))
        assertSame(unsupported, merge(decoded, unsupported, unsupported))
    }

    @Test
    fun `partially understood documents are kept byte for byte instead of rewritten`() {
        val document = """{ "version":1,"variables":[{"name":"TOKEN","source":{"kind":"literal",""" +
            """"value":"value","future":true}}]}"""
        val partial = requireNotNull(EnvironmentFormat.decode(document))
        val decoded = payload(literal("OTHER", "other"))

        assertEquals(false, partial.lossless)
        assertSame(partial, merge(partial, decoded))
        assertSame(partial, merge(decoded, partial, partial))
        assertSame(decoded, merge(decoded, partial))
    }

    @Test
    fun `invalid or unsupported variables fall back without changing the selected payload`() {
        val invalid = listOf(
            payload(literal("BAD-NAME", "value")),
            payload(literal("TOKEN", "nul\u0000value")),
            payload(EnvironmentVariable(
                "TOKEN",
                EnvironmentValueSource.Unsupported(Json.parseToJsonElement("""{"kind":"future","secret":"value"}""")),
            )),
        )
        val valid = payload(literal("OTHER", "other"))

        invalid.forEach { original ->
            assertSame(original, merge(original, valid))
            assertSame(valid, merge(valid, original))
        }
    }

    @Test
    fun `duplicate names trigger fallback instead of adding votes`() {
        val duplicate = payload(literal("TOKEN", "duplicate"), literal("TOKEN", "duplicate"))
        val valid = payload(literal("TOKEN", "other"), literal("EXTRA", "extra"))

        assertSame(duplicate, merge(duplicate, valid))
        assertSame(valid, merge(duplicate, valid, valid))
    }

    @Test
    fun `combined variable count over the limit falls back to a complete payload`() {
        val first = payload(*Array(EnvironmentLimits.MAX_VARIABLES) {
            EnvironmentVariable("V$it", EnvironmentValueSource.Unset)
        })
        val extra = payload(literal("EXTRA", "value"))

        assertSame(first, merge(first, extra))
        assertSame(extra, merge(first, extra, extra))
    }

    @Test
    fun `variable count at the limit can still merge`() {
        val first = payload(*Array(EnvironmentLimits.MAX_VARIABLES - 1) {
            EnvironmentVariable("V$it", EnvironmentValueSource.Unset)
        })
        val extra = literal("EXTRA", "value")

        val merged = assertIs<EnvironmentPayload.Decoded>(merge(first, payload(extra)))

        assertEquals(first.definition.variables + extra, merged.definition.variables)
    }

    @Test
    fun `overlong variable names trigger whole payload fallback`() {
        val longName = payload(literal("A".repeat(EnvironmentLimits.MAX_VARIABLE_NAME_LENGTH + 1), "value"))
        val valid = payload(literal("OTHER", "other"))

        assertSame(longName, merge(longName, valid))
        assertSame(valid, merge(valid, longName))
    }

    @Test
    fun `combined UTF8 document size over the limit falls back without truncation`() {
        val half = "é".repeat(EnvironmentFormat.MAX_PAYLOAD_BYTES / 4)
        val first = payload(literal("FIRST", half))
        val second = payload(literal("SECOND", half))

        assertSame(first, merge(first, second))
        assertSame(second, merge(first, second, second))
    }

    @Test
    fun `environment types and payloads each require all inputs to share an account`() {
        val environment = createSecret(id = "environment", accountId = "first", type = DSecret.Type.Environment)
        val other = createSecret(id = "other", accountId = "second")
        val withPayload = environment.copy(
            type = DSecret.Type.Login,
            environment = payload(literal("TOKEN", "private value")),
        )

        for (input in listOf(environment, withPayload)) {
            for (ciphers in listOf(listOf(input, other), listOf(other, input))) {
                val error = assertFailsWith<IllegalArgumentException> { CipherMergeImpl()(ciphers) }
                assertEquals("Environments can only be merged within one account", error.message)
            }
        }
    }

    @Test
    fun `non environment merges still allow different accounts`() {
        val first = createSecret(id = "first", accountId = "first", tags = listOf("one"))
        val second = createSecret(id = "second", accountId = "second", tags = listOf("two"))

        val merged = CipherMergeImpl()(listOf(first, second))

        assertEquals(first.accountId, merged.accountId)
        assertEquals(listOf("one", "two"), merged.tags)
        assertNull(merged.environment)
    }

    private fun merge(vararg payloads: EnvironmentPayload?): EnvironmentPayload? = CipherMergeImpl()(
        payloads.mapIndexed { index, payload ->
            createSecret(id = "environment-$index", type = DSecret.Type.Environment).copy(environment = payload)
        },
    ).environment

    private fun payload(vararg variables: EnvironmentVariable): EnvironmentPayload.Decoded {
        val definition = EnvironmentDefinition(variables.toList())
        return EnvironmentPayload.Decoded(definition, EnvironmentFormat.encode(definition), lossless = true)
    }

    private fun literal(name: String, value: String) = EnvironmentVariable(name, EnvironmentValueSource.Literal(value))
}

private const val FIRST_CIPHER_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private const val SECOND_CIPHER_ID = "c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"
