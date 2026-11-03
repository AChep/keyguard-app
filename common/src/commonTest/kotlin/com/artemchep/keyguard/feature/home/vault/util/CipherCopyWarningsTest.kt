package com.artemchep.keyguard.feature.home.vault.util

import com.artemchep.keyguard.test.environmentPayload
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.test.createSecret
import com.artemchep.keyguard.ui.SimpleNote
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CipherCopyWarningsTest {
    @Test
    fun `warnings follow the affected source accounts in a mixed batch`() = runTest {
        val linked = createSecret("linked", accountId = "links", links = listOf(DSecret.Link("target")))
        val referencing = createSecret("environment", accountId = "references", type = DSecret.Type.Environment).copy(
            environment = environmentPayload(EnvironmentDefinition(listOf(
                EnvironmentVariable(
                    "PASSWORD", EnvironmentValueSource.Reference("target", EnvironmentFieldReference.Password),
                ),
            ))),
        )
        val unsupported = createSecret("unsupported", accountId = "unknown", type = DSecret.Type.Environment)
        val warnings = cipherCopyWarnings(listOf(linked, referencing, unsupported)) { "warning" }

        assertEquals(3, warnings.size)
        assertTrue(warnings.none { it.appliesTo(null) })
        assertEquals(2, warnings.count { it.appliesTo("links") })
        assertEquals(2, warnings.count { it.appliesTo("references") })
        assertEquals(3, warnings.count { it.appliesTo("other") })
        assertTrue(warnings.filter { it.appliesTo("links") }.any { it.blocksConfirmation })
        assertFalse(warnings.filter { it.appliesTo("unknown") }.any { it.blocksConfirmation })
        assertEquals(SimpleNote.Type.ERROR, warnings.single { it.blocksConfirmation }.note.type)
    }

    @Test
    fun `same-account selection has no warnings and literal-only items need none`() = runTest {
        val linked = createSecret("linked", links = listOf(DSecret.Link("target")))
        val warnings = cipherCopyWarnings(listOf(linked)) { "warning" }
        assertTrue(warnings.none { it.appliesTo(linked.accountId) })
        assertTrue(warnings.single().appliesTo("other"))

        val literal = createSecret("literal", type = DSecret.Type.Environment).copy(
            environment = environmentPayload(EnvironmentDefinition(listOf(
                EnvironmentVariable("LITERAL", EnvironmentValueSource.Literal("kept")),
                EnvironmentVariable("UNSET", EnvironmentValueSource.Unset),
            ))),
        )
        assertTrue(cipherCopyWarnings(listOf(literal, createSecret("plain"))) { "warning" }.isEmpty())
    }

    @Test
    fun `unknown decoded sources block cross-account confirmation`() = runTest {
        val source = createSecret("unknown", type = DSecret.Type.Environment).copy(
            environment = environmentPayload(EnvironmentDefinition(listOf(
                EnvironmentVariable("FUTURE", EnvironmentValueSource.Unsupported(JsonPrimitive("future"))),
            ))),
        )
        val warning = cipherCopyWarnings(listOf(source)) { "warning" }.single()
        assertTrue(warning.blocksConfirmation)
        assertTrue(warning.appliesTo("other"))
        assertFalse(warning.appliesTo(source.accountId))
    }
}
