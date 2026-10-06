package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import arrow.core.Either
import arrow.core.left
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickSearchAutotypeMenuTest {
    @Test
    fun `shift chord opens the menu on both desktop platforms without typing`() {
        val state = QuickSearchState(actions = listOf(QuickSearchAction(
            type = QuickSearchActionType.Autotype,
            title = "Auto-type",
            shortcut = quickSearchShortcut(QuickSearchActionType.Autotype),
        )))
        for (platform in listOf(Platform.Desktop.Windows, Platform.Desktop.MacOS.Jvm)) {
            val input = QuickSearchKeyInput(
                key = Key.T,
                type = KeyEventType.KeyDown,
                isShiftPressed = true,
                isCtrlPressed = platform is Platform.Desktop.Windows,
                isMetaPressed = platform is Platform.Desktop.MacOS,
            )
            assertEquals(QuickSearchKeyEventAction.OpenAutotypeMenu, quickSearchKeyEventAction(input, state, platform))
            assertNull(quickSearchKeyEventAction(input.copy(type = KeyEventType.KeyUp), state, platform))
            assertNull(quickSearchKeyEventAction(input, QuickSearchState(), platform))
            assertEquals(
                QuickSearchKeyEventAction.PerformShortcutAction(QuickSearchActionType.Autotype),
                quickSearchKeyEventAction(input.copy(isShiftPressed = false), state, platform),
            )
        }
    }

    @Test
    fun `numbers immediately select the same field on both keyboard rows`() {
        val keys = mapOf(
            QuickSearchAutotypeField.Username to listOf(Key.One, Key.NumPad1),
            QuickSearchAutotypeField.Password to listOf(Key.Two, Key.NumPad2),
            QuickSearchAutotypeField.OneTimeCode to listOf(Key.Three, Key.NumPad3),
        )
        for ((field, fieldKeys) in keys) {
            for (key in fieldKeys) {
                assertEquals(QuickSearchAutotypeMenuKeyAction.Perform(field), menuAction(key))
            }
        }
        assertEquals(listOf(1, 2, 3), QuickSearchAutotypeField.entries.map { it.number })
    }

    @Test
    fun `missing username does not renumber password or allow disabled actions`() {
        val available = listOf(QuickSearchAutotypeField.Password, QuickSearchAutotypeField.OneTimeCode)
        assertNull(menuAction(Key.One, available))
        assertEquals(
            QuickSearchAutotypeMenuKeyAction.Perform(QuickSearchAutotypeField.Password),
            menuAction(Key.Two, available),
        )
        assertEquals(
            QuickSearchAutotypeMenuKeyAction.Perform(QuickSearchAutotypeField.OneTimeCode),
            menuAction(Key.Three, available),
        )
    }

    @Test
    fun `arrows skip disabled fields and enter chooses the highlighted field`() {
        val available = listOf(QuickSearchAutotypeField.Username, QuickSearchAutotypeField.OneTimeCode)
        assertEquals(
            QuickSearchAutotypeMenuKeyAction.Select(QuickSearchAutotypeField.OneTimeCode),
            menuAction(Key.DirectionDown, available),
        )
        assertEquals(
            QuickSearchAutotypeMenuKeyAction.Select(QuickSearchAutotypeField.OneTimeCode),
            menuAction(Key.DirectionUp, available),
        )
        assertEquals(
            QuickSearchAutotypeMenuKeyAction.Perform(QuickSearchAutotypeField.OneTimeCode),
            menuAction(Key.Enter, available, QuickSearchAutotypeField.OneTimeCode),
        )
        assertEquals(QuickSearchAutotypeMenuKeyAction.Dismiss, menuAction(Key.Escape))
    }

    @Test
    fun `key releases modifiers and unrelated keys do not trigger a field`() {
        val input = QuickSearchKeyInput(Key.Two, KeyEventType.KeyDown)
        for (ignored in listOf(
            input.copy(type = KeyEventType.KeyUp),
            input.copy(isCtrlPressed = true),
            input.copy(isMetaPressed = true),
            input.copy(isShiftPressed = true),
            input.copy(isAltPressed = true),
            input.copy(key = Key.T),
            input.copy(key = Key.Four),
        )) {
            assertNull(quickSearchAutotypeMenuKeyAction(
                ignored,
                QuickSearchAutotypeField.entries,
                QuickSearchAutotypeField.Username,
            ))
        }
    }

    @Test
    fun `individual fields resolve without including the other credential`() = runTest {
        val secret = createSecret(login = DSecret.Login(username = "person@example.com", password = "test-password"))
        val generator = RecordingGetTotpCode()
        val username = assertNotNull(quickSearchAutotypePayload(secret, QuickSearchAutotypeField.Username, generator))()
        assertEquals("person@example.com", username?.username)
        assertEquals("", username?.password)
        val password = assertNotNull(quickSearchAutotypePayload(secret, QuickSearchAutotypeField.Password, generator))()
        assertEquals("", password?.username)
        assertEquals("test-password", password?.password)
        assertEquals(0, generator.calls)
    }

    @Test
    fun `one time code is generated only when the operation resolves its payload`() = runTest {
        val secret = createSecret(login = DSecret.Login(totp = createTotp()))
        val generator = RecordingGetTotpCode()
        val payload = assertNotNull(quickSearchAutotypePayload(secret, QuickSearchAutotypeField.OneTimeCode, generator))
        assertEquals(0, generator.calls)
        generator.code = "654321"
        val login = payload()
        assertEquals(1, generator.calls)
        assertEquals("", login?.username)
        assertEquals("654321", login?.password)
        assertEquals(listOf(QuickSearchAutotypeField.OneTimeCode), quickSearchAutotypeFields(secret))
        assertTrue(QuickSearchActionType.Autotype in quickSearchActionTypes(secret, autotypeAvailable = true))
        assertFalse(QuickSearchActionType.Autotype in quickSearchActionTypes(secret, autotypeAvailable = false))
    }

    @Test
    fun `unavailable and protected fields never produce a payload`() {
        val secret = createSecret(login = DSecret.Login(username = "", password = "password", totp = createTotp()))
        val generator = RecordingGetTotpCode()
        assertNull(quickSearchAutotypePayload(secret, QuickSearchAutotypeField.Username, generator))
        for (unavailable in listOf(secret.copy(reprompt = true), secret.copy(type = DSecret.Type.Card))) {
            assertTrue(quickSearchAutotypeFields(unavailable).isEmpty())
            for (field in QuickSearchAutotypeField.entries) {
                assertNull(quickSearchAutotypePayload(unavailable, field, generator))
            }
        }
        assertEquals(0, generator.calls)
    }

    @Test
    fun `failed code generation does not produce text to type`() = runTest {
        val generator = object : GetTotpCode {
            override fun invoke(p1: TotpToken): Flow<Either<Throwable, TotpCode>> =
                flowOf(IllegalArgumentException("Invalid test token").left())
        }
        val payload = assertNotNull(quickSearchAutotypePayload(
            createSecret(login = DSecret.Login(totp = createTotp())),
            QuickSearchAutotypeField.OneTimeCode,
            generator,
        ))
        assertNull(payload())
    }

    private fun menuAction(
        key: Key,
        available: List<QuickSearchAutotypeField> = QuickSearchAutotypeField.entries,
        selected: QuickSearchAutotypeField = QuickSearchAutotypeField.Username,
    ) = quickSearchAutotypeMenuKeyAction(QuickSearchKeyInput(key, KeyEventType.KeyDown), available, selected)

    private class RecordingGetTotpCode : GetTotpCode {
        var calls = 0
        var code = "123456"
        override fun invoke(p1: TotpToken): Flow<Either<Throwable, TotpCode>> {
            calls += 1
            return successTotpCode(code)(p1)
        }
    }
}
