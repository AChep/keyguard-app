package com.artemchep.keyguard.feature.auth.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextFieldModelTest {
    @Test
    fun handle_id_flows_into_model_state_via_toModel() {
        val handle = TextFieldHandle(MutableStateFlow(TextCell("hello", revision = 3)), id = "email")
        val model = handle.toModel(hint = "Email")

        assertEquals("email", model.state.id)
        assertEquals("hello", model.state.text)
        assertEquals(3, model.state.revision)
        assertEquals("Email", model.state.hint)
        // Editable because the handle wires onChange.
        assertTrue(model.state.editable)
        assertEquals(model.state.text, model.text)
        assertEquals(model.state.revision, model.textRevision)
    }

    @Test
    fun of_takes_id_from_handle_and_derives_editable() {
        val handle = TextFieldHandle(MutableStateFlow(TextCell("pw")), id = "password")
        val cell = handle.sink.value

        val editable = TextFieldModel.of(
            cell = cell,
            handle = handle,
            validated = Validated.Success("pw"),
        )
        assertEquals("password", editable.state.id)
        assertTrue(editable.state.editable)

        val readOnly = TextFieldModel.of(
            cell = cell,
            handle = handle,
            validated = Validated.Success("pw"),
            onChange = null,
        )
        assertEquals("password", readOnly.state.id)
        assertFalse(readOnly.state.editable)
    }

    @Test
    fun convenience_constructor_defaults_id_and_mirrors_via_getters() {
        val model = TextFieldModel(
            text = "abc",
            textRevision = 2,
            hint = "hint",
            error = "err",
        )
        assertEquals("", model.state.id)
        assertEquals("abc", model.text)
        assertEquals(2, model.textRevision)
        assertEquals("hint", model.hint)
        assertEquals("err", model.error)
        // No onChange supplied -> not editable.
        assertFalse(model.state.editable)
    }

    @Test
    fun empty_is_blank_and_non_editable() {
        val empty = TextFieldModel.empty
        assertEquals("", empty.state.id)
        assertEquals("", empty.text)
        assertFalse(empty.state.editable)
        assertNull(empty.error)
    }
}
