package com.artemchep.keyguard.apple.model

import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Bridge reachability checks use in-memory fields, without an application core or vault. */
class TextFieldSnapshotTest {
    @Test
    fun editsReachOnlyTheFieldIdentifiedByTheSnapshot() {
        var email = ""
        var password = ""
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val emailField = TextFieldModel(id = "login.email", text = email, onChange = { email = it })
        val passwordField = TextFieldModel(id = "login.password", text = password, onChange = { password = it })
        val emailSnapshot = emailField.toFieldSnapshot(handlers)
        val passwordSnapshot = passwordField.toFieldSnapshot(handlers)

        assertTrue(emailSnapshot.editable)
        assertTrue(passwordSnapshot.editable)
        handlers.getValue(emailSnapshot.id)("synthetic@example.com")
        assertEquals("synthetic@example.com", email)
        assertEquals("", password)
        handlers.getValue(passwordSnapshot.id)("synthetic-password")
        assertEquals("synthetic@example.com", email)
        assertEquals("synthetic-password", password)
        assertEquals(
            email,
            emailField.copy(state = emailField.state.copy(text = email)).toFieldSnapshot().text,
        )
        assertEquals(
            password,
            passwordField.copy(state = passwordField.state.copy(text = password)).toFieldSnapshot().text,
        )
    }

    @Test
    fun readOnlyFieldsExposeNoEditHandler() {
        val handlers = mutableMapOf<String, (String) -> Unit>()
        val snapshot = TextFieldModel(id = "readonly", text = "synthetic-value").toFieldSnapshot(handlers)

        assertFalse(snapshot.editable)
        assertEquals("synthetic-value", snapshot.text)
        assertTrue(handlers.isEmpty())
    }
}
