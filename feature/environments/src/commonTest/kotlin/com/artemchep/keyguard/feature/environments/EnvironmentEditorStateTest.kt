package com.artemchep.keyguard.feature.environments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentEditorStateTest {
    @Test
    fun variableLimitsApplyToSavingAndImporting() {
        val cases = listOf(
            listOf("A".repeat(EnvironmentLimits.MAX_VARIABLE_NAME_LENGTH)) to true,
            listOf("A".repeat(EnvironmentLimits.MAX_VARIABLE_NAME_LENGTH + 1)) to false,
            List(EnvironmentLimits.MAX_VARIABLES) { "V$it" } to true,
            List(EnvironmentLimits.MAX_VARIABLES + 1) { "V$it" } to false,
        )
        cases.forEach { (names, accepted) ->
            val definition = EnvironmentDefinition(names.map {
                EnvironmentVariable(it, EnvironmentValueSource.Literal(""))
            })
            // Keep these examples under the serialized-size limit to isolate name/count validation.
            EnvironmentFormat.encode(definition)
            val editor = EnvironmentEditorState(
                accountId = "account", name = "Development", definition = definition,
                importText = names.joinToString("\n") { "$it=" },
            )
            assertEquals(accepted, editor.canSave)
            assertEquals(!accepted, editor.exceedsLimits)
            assertEquals(!accepted, editor.previewImport().error)
        }
    }

    @Test
    fun importPreviewRequiresExplicitApplyAndDoesNotRevealValues() {
        val editor = EnvironmentEditorState(
            id = "item", accountId = "account", name = "Development",
            importText = "TOKEN=secret\nEMPTY=\nUNSET",
        )
        val preview = editor.previewImport()
        assertTrue(preview.editable)
        assertTrue(preview.definition.variables.isEmpty())
        val applied = preview.applyImport()
        assertTrue(applied.editable)
        assertTrue(applied.canSave)
        assertTrue(applied.revealed.isEmpty())
        assertEquals("", applied.importText)
        assertEquals(EnvironmentValueSource.Literal(""), applied.definition.variables[1].source)
        assertEquals(EnvironmentValueSource.Unset, applied.definition.variables[2].source)
    }

    @Test
    fun invalidImportPreservesExistingDefinition() {
        val definition = EnvironmentDefinition(
            listOf(EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("old"))),
        )
        val editor = EnvironmentEditorState(definition = definition, importText = "TOKEN=new\nTOKEN=other")
        val preview = editor.previewImport()
        assertTrue(preview.error)
        assertEquals(definition, preview.applyImport().definition)
    }

    @Test
    fun unsupportedEditorIgnoresVariableUpdates() {
        val editor = EnvironmentEditorState(editable = false, importText = "TOKEN=new")
        assertEquals(editor, editor.previewImport().applyImport())
        assertEquals(editor, editor.updateVariable(0) { EnvironmentVariable("TOKEN", EnvironmentValueSource.Unset) })
    }
}
