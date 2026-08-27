package com.artemchep.keyguard.feature.passwordmemory

import com.artemchep.keyguard.common.model.ToastMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordMemoryStateProducerTest {
    @Test
    fun `matching passwords are correct`() {
        val result = evaluatePasswordMemoryAttempt(
            expectedPassword = "correct horse battery staple",
            attemptedPassword = "correct horse battery staple",
        )

        assertEquals(PasswordMemoryResult.Correct, result)
    }

    @Test
    fun `different passwords are incorrect`() {
        val result = evaluatePasswordMemoryAttempt(
            expectedPassword = "correct horse battery staple",
            attemptedPassword = "wrong password",
        )

        assertEquals(PasswordMemoryResult.Incorrect, result)
    }

    @Test
    fun `password comparison is case sensitive`() {
        val result = evaluatePasswordMemoryAttempt(
            expectedPassword = "Password",
            attemptedPassword = "password",
        )

        assertEquals(PasswordMemoryResult.Incorrect, result)
    }

    @Test
    fun `password comparison does not trim whitespace`() {
        val leadingWhitespace = evaluatePasswordMemoryAttempt(
            expectedPassword = "password",
            attemptedPassword = " password",
        )
        val trailingWhitespace = evaluatePasswordMemoryAttempt(
            expectedPassword = "password",
            attemptedPassword = "password ",
        )

        assertEquals(PasswordMemoryResult.Incorrect, leadingWhitespace)
        assertEquals(PasswordMemoryResult.Incorrect, trailingWhitespace)
    }

    @Test
    fun `empty form is neutral and cannot be verified`() {
        val form = PasswordMemoryForm()

        assertFalse(form.canVerify)
        assertNull(form.result)
    }

    @Test
    fun `form reports correct and incorrect attempts`() {
        val correct = PasswordMemoryForm()
            .withPassword("password")
            .verify(expectedPassword = "password")
        val incorrect = PasswordMemoryForm()
            .withPassword("wrong")
            .verify(expectedPassword = "password")

        assertFalse(correct.canVerify)
        assertEquals(PasswordMemoryResult.Correct, correct.result)
        assertTrue(incorrect.canVerify)
        assertEquals(PasswordMemoryResult.Incorrect, incorrect.result)
    }

    @Test
    fun `editing the password clears the previous result`() {
        val form = PasswordMemoryForm()
            .withPassword("wrong")
            .verify(expectedPassword = "password")
            .withPassword("password")

        assertNull(form.result)
    }

    @Test
    fun `matching attempt closes the dialog and shows a success toast`() = runTest {
        val fixture = StateFixture()
        val initial = fixture.state.first()
        assertNull(initial.onVerify)
        assertNull(initial.password.error)
        assertNull(initial.password.vl)
        assertNotNull(initial.password.onChange).invoke("password")

        assertNotNull(fixture.state.first().onVerify).invoke()

        assertEquals(1, fixture.closeCount)
        val message = fixture.messages.single()
        assertEquals("Password matches", message.title)
        assertEquals(ToastMessage.Type.SUCCESS, message.type)
        assertNull(message.text)
        val verified = fixture.state.first()
        assertNull(verified.password.error)
        assertNull(verified.password.vl)
        assertNull(verified.onVerify)
    }

    @Test
    fun `incorrect attempt stays open and can be corrected`() = runTest {
        val fixture = StateFixture()
        assertNotNull(fixture.state.first().password.onChange).invoke("wrong")

        assertNotNull(fixture.state.first().onVerify).invoke()

        val incorrect = fixture.state.first()
        assertEquals("Incorrect password", incorrect.password.error)
        assertEquals(0, fixture.closeCount)
        assertTrue(fixture.messages.isEmpty())
        assertNotNull(incorrect.onVerify)

        assertNotNull(incorrect.password.onChange).invoke("password")
        val edited = fixture.state.first()
        assertNull(edited.password.error)
        assertNotNull(edited.onVerify).invoke()

        assertEquals(1, fixture.closeCount)
        assertEquals(1, fixture.messages.size)
    }

    @Test
    fun `stale verify callback ignores an empty attempt`() = runTest {
        val fixture = StateFixture()
        assertNotNull(fixture.state.first().password.onChange).invoke("password")
        val entered = fixture.state.first()
        val onVerify = assertNotNull(entered.onVerify)
        assertNotNull(entered.password.onChange).invoke("")

        onVerify()

        val empty = fixture.state.first()
        assertNull(empty.onVerify)
        assertNull(empty.password.error)
        assertEquals(0, fixture.closeCount)
        assertTrue(fixture.messages.isEmpty())
    }

    @Test
    fun `repeated verify callback emits success only once`() = runTest {
        val fixture = StateFixture()
        assertNotNull(fixture.state.first().password.onChange).invoke("password")
        val onVerify = assertNotNull(fixture.state.first().onVerify)

        onVerify()
        onVerify()

        assertEquals(1, fixture.closeCount)
        assertEquals(1, fixture.messages.size)
    }

    @Test
    fun `closing without verification does not show a toast`() = runTest {
        val fixture = StateFixture()

        assertNotNull(fixture.state.first().onClose).invoke()

        assertEquals(1, fixture.closeCount)
        assertTrue(fixture.messages.isEmpty())
    }

    private class StateFixture {
        val messages = mutableListOf<ToastMessage>()
        var closeCount = 0
        val state: Flow<PasswordMemoryState> = passwordMemoryStateFlow(
            expectedPassword = "password",
            incorrectPassword = "Incorrect password",
            passwordMatches = "Password matches",
            onMessage = { messages += it },
            onClose = { closeCount += 1 },
        )
    }
}
