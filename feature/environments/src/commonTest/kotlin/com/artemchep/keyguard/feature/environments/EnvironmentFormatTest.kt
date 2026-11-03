package com.artemchep.keyguard.feature.environments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the stored format with hand-written documents. Installed versions read
 * these documents, so a failure here is a compatibility break, not a stale test.
 */
class EnvironmentFormatTest {
    private val cipherId = "6f1c2c1e-6c8e-4b3e-9a51-6a7f0d2b9c41"

    private val v1 = """{"version":1,"variables":[""" +
        """{"name":"EMPTY","source":{"kind":"literal","value":""}},""" +
        """{"name":"UNSET","source":{"kind":"unset"}},""" +
        """{"name":"DB_PASSWORD","source":{"kind":"cipherField","cipherId":"$cipherId","field":"password"}},""" +
        """{"name":"API_KEY","source":""" +
        """{"kind":"cipherField","cipherId":"$cipherId","field":"custom","name":"api key"}},""" +
        """{"name":"TOKEN","source":{"kind":"literal","value":"🔑 Київ"}}""" +
        """]}"""

    private val v1Definition = EnvironmentDefinition(listOf(
        EnvironmentVariable("EMPTY", EnvironmentValueSource.Literal("")),
        EnvironmentVariable("UNSET", EnvironmentValueSource.Unset),
        EnvironmentVariable(
            "DB_PASSWORD",
            EnvironmentValueSource.Reference(cipherId, EnvironmentFieldReference.Password),
        ),
        EnvironmentVariable(
            "API_KEY",
            EnvironmentValueSource.Reference(cipherId, EnvironmentFieldReference.Custom("api key")),
        ),
        EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("🔑 Київ")),
    ))

    @Test
    fun `v1 document decodes and encodes to the same bytes`() {
        val decoded = assertNotNull(EnvironmentFormat.decode(v1))
        assertEquals(v1Definition, decoded.definition)
        assertEquals(v1, decoded.document)
        assertTrue(decoded.lossless)
        assertEquals(v1Definition, decoded.editableDefinition())
        assertEquals(v1, EnvironmentFormat.encode(v1Definition))
    }

    @Test
    fun `unknown keys are ignored but make the document read only`() {
        val documents = listOf(
            """{"version":1,"variables":[],"origin":"future"}""" to EnvironmentDefinition(),
            """{"version":1,"variables":[{"name":"A","description":"x","source":{"kind":"unset"}}]}""" to
                definition("A" to EnvironmentValueSource.Unset),
            """{"version":1,"variables":[{"name":"A","source":{"kind":"unset","note":"x"}}]}""" to
                definition("A" to EnvironmentValueSource.Unset),
            """{"version":1,"variables":[{"name":"A","source":{"kind":"literal","value":"v","secret":true}}]}""" to
                definition("A" to EnvironmentValueSource.Literal("v")),
            """{"version":1,"variables":[{"name":"A","source":""" +
                """{"kind":"cipherField","cipherId":"$cipherId","field":"password","label":"Prod DB"}}]}""" to
                definition("A" to EnvironmentValueSource.Reference(cipherId, EnvironmentFieldReference.Password)),
        )
        documents.forEach { (document, definition) ->
            val decoded = assertNotNull(EnvironmentFormat.decode(document), document)
            assertEquals(definition, decoded.runnableDefinition(), document)
            assertFalse(decoded.lossless, document)
            assertNull(decoded.editableDefinition(), document)
            // Written back as it was read.
            assertEquals(document, decoded.document)
        }
    }

    @Test
    fun `documents of an unknown version are not read`() {
        listOf(
            """{"version":2,"variables":[]}""",
            """{"variables":[]}""",
            """{"version":1}""",
            """[]""",
            """not json""",
        ).forEach { document ->
            assertNull(EnvironmentFormat.decode(document), document)
        }
    }

    @Test
    fun `an unknown kind makes only its variable unsupported`() {
        val raw = """{"kind":"itemFieldReference","field":{"id":"future"}}"""
        val document = """{"version":1,"variables":[""" +
            """{"name":"A","source":$raw},""" +
            """{"name":"B","source":{"kind":"literal","value":"b"}}""" +
            """]}"""
        val decoded = assertNotNull(EnvironmentFormat.decode(document))
        assertIs<EnvironmentValueSource.Unsupported>(decoded.definition.variables[0].source)
        assertEquals(EnvironmentValueSource.Literal("b"), decoded.definition.variables[1].source)
        // The unknown source is kept verbatim, so editing the other variables loses nothing...
        assertTrue(decoded.lossless)
        // ...but the definition can neither run nor be edited by this version.
        assertNull(decoded.runnableDefinition())
        assertNull(decoded.editableDefinition())
    }

    @Test
    fun `names are checked before delivery, not when reading`() {
        val document = """{"version":1,"variables":[""" +
            """{"name":"BAD-NAME","source":{"kind":"literal","value":"a"}},""" +
            """{"name":"A","source":{"kind":"literal","value":"a"}},""" +
            """{"name":"A","source":{"kind":"literal","value":"b"}}""" +
            """]}"""
        val decoded = assertNotNull(EnvironmentFormat.decode(document))
        assertTrue(decoded.lossless)
        val result = assertIs<EnvironmentResolutionResult.Unresolved>(EnvironmentResolver.resolve(decoded.definition))
        assertEquals(
            setOf(EnvironmentIssueType.InvalidName, EnvironmentIssueType.DuplicateName),
            result.issues.map { it.type }.toSet(),
        )
    }

    @Test
    fun `documents above the write limit are still read`() {
        val value = "a".repeat(EnvironmentFormat.MAX_PAYLOAD_BYTES)
        val document = """{"version":1,"variables":[{"name":"A","source":{"kind":"literal","value":"$value"}}]}"""
        val decoded = assertNotNull(EnvironmentFormat.decode(document))
        assertFailsWith<IllegalArgumentException> { EnvironmentFormat.encode(decoded.definition) }

        val tooLarge = document.replace(value, "a".repeat(EnvironmentFormat.MAX_READ_BYTES))
        assertNull(EnvironmentFormat.decode(tooLarge))
    }

    private fun definition(vararg variables: Pair<String, EnvironmentValueSource>) =
        EnvironmentDefinition(variables.map { (name, source) -> EnvironmentVariable(name, source) })
}
