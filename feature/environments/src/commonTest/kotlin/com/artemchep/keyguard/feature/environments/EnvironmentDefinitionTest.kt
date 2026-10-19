package com.artemchep.keyguard.feature.environments

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentDefinitionTest {
    @Test
    fun `unset is distinct from an empty literal and blocks all delivery`() {
        val definition = EnvironmentDefinition(listOf(
            EnvironmentVariable("EMPTY", EnvironmentValueSource.Literal("")),
            EnvironmentVariable("REQUIRED", EnvironmentValueSource.Unset),
        ))
        assertTrue(EnvironmentResolver.validate(definition).isEmpty())
        val result = assertIs<EnvironmentResolutionResult.Unresolved>(EnvironmentResolver.resolve(definition))
        assertEquals(listOf(EnvironmentIssue("REQUIRED", EnvironmentIssueType.Unset)), result.issues)
        assertFailsWith<EnvironmentDotenvException> { EnvironmentDotenv.format(definition) }
    }

    @Test
    fun `unknown sources survive serialization and cannot resolve`() {
        val raw = Json.parseToJsonElement("""{"kind":"itemFieldReference","field":{"id":"future"}}""")
        val definition = EnvironmentDefinition(listOf(
            EnvironmentVariable("TOKEN", EnvironmentValueSource.Unsupported(raw)),
        ))
        val decoded = EnvironmentFormat.decode(EnvironmentFormat.encode(definition))!!.definition
        assertEquals(definition, decoded)
        assertTrue(decoded.hasUnsupportedSources)
        assertIs<EnvironmentResolutionResult.Unresolved>(EnvironmentResolver.resolve(decoded))
    }

    @Test
    fun `names duplicates and NUL values are rejected without including values in errors`() {
        val definition = EnvironmentDefinition(listOf(
            EnvironmentVariable("BAD-NAME", EnvironmentValueSource.Literal("secret")),
            EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("secret\u0000")),
            EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("secret")),
        ))
        val issues = EnvironmentResolver.validate(definition)
        assertEquals(
            setOf(
                EnvironmentIssueType.InvalidName,
                EnvironmentIssueType.InvalidValue,
                EnvironmentIssueType.DuplicateName,
            ),
            issues.map { it.type }.toSet(),
        )
        assertTrue(issues.none { "secret" in it.toString() })
        assertTrue("secret" !in definition.toString())
    }

    @Test
    fun `literal shell and reference syntax stays literal`() {
        val value = "\${TOKEN} $(command) keyguard://cipher/id"
        val definition = EnvironmentDefinition(
            listOf(EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal(value))),
        )
        val resolved = assertIs<EnvironmentResolutionResult.Resolved>(EnvironmentResolver.resolve(definition))
        assertEquals(mapOf("TOKEN" to value), resolved.snapshot.variables)
        assertTrue(value !in resolved.toString())
    }

    @Test
    fun `format enforces aggregate UTF8 size and version`() {
        val definition = EnvironmentDefinition(
            listOf(EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("a".repeat(65_536)))),
        )
        assertFailsWith<IllegalArgumentException> { EnvironmentFormat.encode(definition) }
        assertNull(EnvironmentFormat.decode("""{"version":2,"variables":[]}"""))
    }

    @Test
    fun `references round trip and malformed references are kept verbatim`() {
        val cipherId = "6f1c2c1e-6c8e-4b3e-9a51-6a7f0d2b9c41"
        val definition = EnvironmentDefinition(listOf(
            EnvironmentVariable(
                "DB_PASSWORD",
                EnvironmentValueSource.Reference(cipherId, EnvironmentFieldReference.Password),
            ),
            EnvironmentVariable(
                "API_KEY",
                EnvironmentValueSource.Reference(cipherId, EnvironmentFieldReference.Custom("api key")),
            ),
        ))
        val decoded = EnvironmentFormat.decode(EnvironmentFormat.encode(definition))!!.definition
        assertEquals(definition, decoded)
        assertTrue(EnvironmentResolver.validate(decoded).isEmpty())
        assertTrue(cipherId !in definition.toString())

        for (raw in listOf(
            // Not a canonical item id.
            """{"kind":"cipherField","cipherId":"6F1C2C1E-6C8E-4B3E-9A51-6A7F0D2B9C41","field":"password"}""",
            """{"kind":"cipherField","cipherId":"local-id","field":"password"}""",
            // Unknown field kind, or blank or missing name.
            """{"kind":"cipherField","cipherId":"$cipherId","field":"username"}""",
            """{"kind":"cipherField","cipherId":"$cipherId","field":"custom","name":" "}""",
            """{"kind":"cipherField","cipherId":"$cipherId","field":"custom"}""",
        )) {
            val element = Json.parseToJsonElement(raw)
            val json = """{"version":1,"variables":[{"name":"A","source":$raw}]}"""
            val source = EnvironmentFormat.decode(json)!!.definition.variables.single().source
            assertEquals(EnvironmentValueSource.Unsupported(element), source, raw)
        }
    }

    @Test
    fun `references resolve through the lookup and fail closed`() {
        val cipherId = "6f1c2c1e-6c8e-4b3e-9a51-6a7f0d2b9c41"
        fun reference(name: String) = EnvironmentValueSource.Reference(cipherId, EnvironmentFieldReference.Custom(name))
        val definition = EnvironmentDefinition(listOf(
            EnvironmentVariable("LITERAL", EnvironmentValueSource.Literal("literal")),
            EnvironmentVariable("FOUND", reference("found")),
        ))
        val lookup = EnvironmentReferenceLookup { reference ->
            when ((reference.field as EnvironmentFieldReference.Custom).name) {
                "found" -> EnvironmentReferenceResolution.Value("secret")
                "ambiguous" -> EnvironmentReferenceResolution.Ambiguous
                "denied" -> EnvironmentReferenceResolution.Denied
                "nul" -> EnvironmentReferenceResolution.Value("a\u0000b")
                else -> EnvironmentReferenceResolution.NotFound
            }
        }
        val resolved = assertIs<EnvironmentResolutionResult.Resolved>(EnvironmentResolver.resolve(definition, lookup))
        assertEquals(mapOf("LITERAL" to "literal", "FOUND" to "secret"), resolved.snapshot.variables)
        // Without access to the vault no reference resolves.
        assertIs<EnvironmentResolutionResult.Unresolved>(EnvironmentResolver.resolve(definition))

        val failing = EnvironmentDefinition(listOf(
            EnvironmentVariable("MISSING", reference("missing")),
            EnvironmentVariable("AMBIGUOUS", reference("ambiguous")),
            EnvironmentVariable("DENIED", reference("denied")),
            EnvironmentVariable("NUL", reference("nul")),
        ))
        val result = assertIs<EnvironmentResolutionResult.Unresolved>(EnvironmentResolver.resolve(failing, lookup))
        assertEquals(
            listOf(
                EnvironmentIssue("MISSING", EnvironmentIssueType.ReferenceNotFound),
                EnvironmentIssue("AMBIGUOUS", EnvironmentIssueType.ReferenceAmbiguous),
                EnvironmentIssue("DENIED", EnvironmentIssueType.ReferenceDenied),
                EnvironmentIssue("NUL", EnvironmentIssueType.InvalidValue),
            ),
            result.issues,
        )
        assertTrue("secret" !in result.toString())
    }

    @Test
    fun `suggested variable names are valid names`() {
        assertEquals("API_KEY", suggestEnvironmentVariableName(EnvironmentFieldReference.Custom("api key"), "Item"))
        assertEquals(
            "POSTGRES_PROD_PASSWORD",
            suggestEnvironmentVariableName(EnvironmentFieldReference.Password, "Postgres (prod)"),
        )
        assertEquals("_1PASSWORD", suggestEnvironmentVariableName(EnvironmentFieldReference.Custom("1password"), ""))
        assertEquals("", suggestEnvironmentVariableName(EnvironmentFieldReference.Custom("ключ"), ""))
    }
}
