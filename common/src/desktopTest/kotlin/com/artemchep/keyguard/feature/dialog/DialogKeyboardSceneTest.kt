@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.use
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.colorpicker.ColorPickerDialog
import com.artemchep.keyguard.feature.colorpicker.ColorPickerState
import com.artemchep.keyguard.feature.confirmation.ConfirmationDialog
import com.artemchep.keyguard.feature.confirmation.ConfirmationForm
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationState
import com.artemchep.keyguard.feature.confirmation.folder.CreateNewFolder
import com.artemchep.keyguard.feature.datepicker.DatePickerDialog
import com.artemchep.keyguard.feature.datepicker.DatePickerState
import com.artemchep.keyguard.feature.fido2.Fido2Prompt
import com.artemchep.keyguard.feature.fido2.NativeFido2Dialog
import com.artemchep.keyguard.feature.gpgagent.tools.publickey.GpgToolsPublicKeyResult
import com.artemchep.keyguard.feature.gpgagent.tools.publickey.GpgToolsPublicKeyRoute
import com.artemchep.keyguard.feature.gpgagent.tools.publickey.GpgToolsPublicKeyScreen
import com.artemchep.keyguard.feature.gpgkey.userids.GpgUserIdsDialog
import com.artemchep.keyguard.feature.gpgkey.userids.GpgUserIdsState
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.timepicker.TimePickerDialog
import com.artemchep.keyguard.feature.timepicker.TimePickerState
import com.artemchep.keyguard.ui.DialogPopup
import com.artemchep.keyguard.ui.icons.generateAccentColors
import com.artemchep.keyguard.ui.wizard.WizardPreview
import com.artemchep.keyguard.util.fido2.Fido2Exception
import com.artemchep.keyguard.util.fido2.Fido2Failure
import com.artemchep.keyguard.util.fido2.Fido2Operation
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalTime
import org.koin.compose.scope.UnboundKoinScope
import org.koin.core.Koin
import org.koin.core.annotation.KoinDelicateAPI
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.qualifier.named
import org.koin.dsl.module

class DialogKeyboardSceneTest {
    @Test
    @OptIn(KoinExperimentalAPI::class, KoinDelicateAPI::class)
    fun passwordSubmitsWithReturnKeypadOrImeOnlyWhenValid() {
        val koin = Koin()
        koin.loadModules(
            listOf(module {
                single<GetPasswordStrength> {
                    object : GetPasswordStrength {
                        override fun invoke(password: String) = ioEffect { PasswordStrength(0L, version = 1L) }
                    }
                }
            }),
        )
        try {
            val scope = koin.createScope("password", named("password"))
            var password by mutableStateOf("")
            val submissions = mutableListOf<String>()
            withScene({
                UnboundKoinScope(scope) {
                    val item = ConfirmationState.Item.StringItem(
                        key = "password",
                        value = password,
                        enabled = true,
                        state = TextFieldModel(text = password, onChange = { password = it }),
                        title = "Password",
                        description = null,
                        sensitive = true,
                        monospace = true,
                        password = true,
                        generator = null,
                    )
                    val disabled = item.copy(
                        key = "disabled",
                        enabled = false,
                        state = TextFieldModel(text = ""),
                    )
                    ConfirmationForm(
                        items = listOf(disabled, item),
                        onConfirm = { submissions += password }.takeIf { password.isNotBlank() },
                    )
                }
            }) {
                press(Key.Enter)
                assertTrue(submissions.isEmpty())
                enterText("typed password")
                press(Key.Enter)
                press(Key.NumPadEnter)
                assertTrue(focused()[SemanticsActions.OnImeAction].action!!.invoke())
                assertEquals(List(3) { "typed password" }, submissions)
            }
        } finally {
            koin.close()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun folderKeepsTheNameOnSubmitAndTab() {
        var name by mutableStateOf("Folder")
        val submissions = mutableListOf<String>()
        val dispatcher = UnconfinedTestDispatcher()
        withScene({
            Column {
                CreateNewFolder(
                    field = TextFieldModel(text = name, onChange = { name = it }),
                    onConfirm = { submissions += name },
                )
                Button(onClick = {}) { Text("Close") }
            }
        }, dispatcher) {
            // The field requests focus after a short delay.
            dispatcher.scheduler.advanceTimeBy(100L)
            draw()
            assertEquals(name, focused()[SemanticsProperties.EditableText].text)
            press(Key.NumPadEnter)
            press(Key.Tab)
            assertEquals(listOf("Folder"), submissions)
            assertEquals("Folder", name)
            assertNull(focused().getOrNull(SemanticsActions.SetText))
            press(Key.Tab, shift = true)
            assertEquals(name, focused()[SemanticsProperties.EditableText].text)
        }
    }

    @Test
    fun gpgIdentityNextSkipsClearAndLastFieldSaves() {
        var name by mutableStateOf("Alice")
        var email by mutableStateOf("alice@example.test")
        var saved = 0
        withScene({
            GpgUserIdsDialog(GpgUserIdsState(
                title = "Identity", message = "", error = null, busy = false, loading = false,
                page = GpgUserIdsState.Page.Edit, identities = emptyList(), history = emptyList(),
                fields = listOf(
                    GpgUserIdsState.Field(
                        GpgUserIdsState.Field.Kind.Name, "Name",
                        TextFieldModel(text = name, onChange = { name = it }),
                    ),
                    GpgUserIdsState.Field(
                        GpgUserIdsState.Field.Kind.Email, "Email",
                        TextFieldModel(text = email, onChange = { email = it }),
                    ),
                ),
                add = null, formats = persistentListOf(),
                confirm = GpgUserIdsState.Action("save", "Save", onClick = { saved++ }),
                back = GpgUserIdsState.Action("back", "Back", onClick = {}),
            ))
        }) {
            assertEquals(name, focused()[SemanticsProperties.EditableText].text)
            press(Key.Enter)
            assertEquals(email, focused()[SemanticsProperties.EditableText].text)
            press(Key.Enter)
            assertEquals("Alice", name)
            assertEquals(1, saved)
        }
    }

    @Test
    fun multilineEnterEditsAndTabMovesFocusInBothDirections() {
        val results = mutableListOf<GpgToolsPublicKeyResult>()
        val transmitter = object : RouteResultTransmitter<GpgToolsPublicKeyResult> {
            override fun invoke(unit: GpgToolsPublicKeyResult) { results += unit }
        }
        withScene({ GpgToolsPublicKeyScreen(GpgToolsPublicKeyRoute.Args(), transmitter) }) {
            press(Key.Enter)
            assertEquals("\n", focused()[SemanticsProperties.EditableText].text)
            press(Key.Tab)
            assertNull(focused().getOrNull(SemanticsActions.SetText))
            press(Key.Tab, shift = true)
            assertEquals("\n", focused()[SemanticsProperties.EditableText].text)
            assertTrue(results.isEmpty())
        }
    }

    @Test
    fun nestedDialogsRestoreFocusToTheirOpeners() {
        var outer by mutableStateOf(false)
        var inner by mutableStateOf(false)
        withScene({
            Button(modifier = Modifier.focusOnDialogOpen(), onClick = { outer = true }) { Text("Open") }
            if (outer) DialogPopup(onDismissRequest = { outer = false }) {
                Button(modifier = Modifier.focusOnDialogOpen(), onClick = { inner = true }) { Text("More") }
                Button(onClick = { outer = false }) { Text("Close outer") }
                if (inner) DialogPopup(onDismissRequest = { inner = false }) {
                    Button(modifier = Modifier.focusOnDialogOpen(), onClick = { inner = false }) { Text("Close inner") }
                }
            }
        }) {
            press(Key.Enter)
            press(Key.Enter)
            assertTrue(inner)
            press(Key.Tab)
            press(Key.Escape, shift = true)
            assertTrue(inner)
            press(Key.Escape)
            assertTrue(outer && !inner)
            assertEquals("More", focused()[SemanticsProperties.Text].single().text)
            press(Key.Escape)
            assertTrue(!outer)
            assertEquals("Open", focused()[SemanticsProperties.Text].single().text)
        }
    }

    @Test
    fun colorOpensOnItsSelectionAndTabLeavesThePalette() {
        var selected by mutableStateOf(37)
        val items = (0 until 64).map { i ->
            ColorPickerState.Item(i, generateAccentColors(hue = 360f * i / 64), onClick = { selected = i })
        }.toPersistentList()
        withScene({
            ColorPickerDialog(Loadable.Ok(ColorPickerState(ColorPickerState.Content(selected, items), {}, {})))
        }) {
            assertTrue(focused()[SemanticsProperties.Selected])
            assertTrue(focused()[SemanticsProperties.ContentDescription].single().startsWith("#"))
            press(Key.DirectionRight)
            press(Key.Enter)
            assertEquals(38, selected)
            press(Key.Tab)
            assertEquals("Close", focused()[SemanticsProperties.Text].single().text)
        }
    }

    @Test
    fun tabLeavesEachDateColumnAndArrowsChooseWithinIt() {
        val months = (1..12).map { DatePickerState.Item(it, "Month $it", onClick = {}) }.toPersistentList()
        val years = (1900..2100).map { DatePickerState.Item(it, it.toString(), onClick = {}) }.toPersistentList()
        withScene({
            DatePickerDialog(Loadable.Ok(DatePickerState(DatePickerState.Content(8, months, 2026, years), {}, {})))
        }) {
            assertEquals("Month 8", focused()[SemanticsProperties.Text].single().text)
            press(Key.DirectionDown)
            assertEquals("Month 9", focused()[SemanticsProperties.Text].single().text)
            press(Key.Tab)
            assertTrue(focused()[SemanticsProperties.Text].single().text.toInt() in 1900..2100)
            press(Key.Tab)
            assertEquals("Close", focused()[SemanticsProperties.Text].single().text)
        }
    }

    @Test
    fun fidoPinRetryFocusesAnEmptyFieldAndSubmitsOncePerAttempt() {
        val attempts = mutableListOf<String?>()
        val prompt = Fido2Prompt(Fido2Operation.Register(ByteArray(32), ByteArray(32))) {}
        withScene({
            NativeFido2Dialog(prompt) { pin ->
                attempts += pin
                when (pin) {
                    null -> throw Fido2Exception(Fido2Failure.PIN_REQUIRED)
                    "wrong" -> throw Fido2Exception(Fido2Failure.INVALID_PIN)
                    else -> byteArrayOf(1)
                }
            }
        }) {
            press(Key.Enter)
            assertEquals(listOf<String?>(null), attempts)
            enterText("wrong")
            press(Key.Enter)
            assertEquals("", focused()[SemanticsProperties.EditableText].text)
            enterText("correct")
            press(Key.NumPadEnter)
            assertEquals(listOf(null, "wrong", "correct"), attempts)
        }
    }

    @Test
    fun timeModeToggleOpensKeyboardEntryWithTheCurrentTime() {
        val initial = LocalTime(10, 25)
        var selected = initial
        withScene({
            TimePickerDialog(Loadable.Ok(TimePickerState(TimePickerState.Content(initial) { selected = it }, {}, {})))
        }) {
            press(Key.Enter)
            val inputs = semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
                .mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }
            assertTrue(inputs.containsAll(listOf("10", "25")), inputs.toString())
            assertEquals(initial, selected)
        }
    }
}

/** Which control a dialog focuses when it opens. */
class DialogEntryFocusSceneTest {
    @Test
    fun checkboxOnlyConfirmationOpensOnCancel() {
        var checked by mutableStateOf(false)
        var denied = 0
        withScene({
            ConfirmationDialog(
                args = ConfirmationRoute.Args(
                    title = "Delete folder",
                    items = listOf(ConfirmationRoute.Args.Item.BooleanItem(key = "trash", title = "Trash items")),
                ),
                state = ConfirmationState(
                    sideEffects = ConfirmationState.SideEffects(),
                    items = Loadable.Ok(
                        listOf(
                            ConfirmationState.Item.BooleanItem(
                                key = "trash", value = checked, enabled = true, title = "Trash items",
                                text = null, textMaxLines = 1, onChange = { checked = it },
                            ),
                        ),
                    ),
                    onDeny = { denied++ },
                    onConfirm = {},
                ),
            )
        }) {
            assertEquals("Cancel", focused()[SemanticsProperties.Text].single().text)
            press(Key.Enter)
            assertFalse(checked)
            assertEquals(1, denied)
        }
    }

    @Test
    fun focusOnDialogOpenDoesNotTakeFocusBackWhenReEnabled() {
        var enabled by mutableStateOf(true)
        withScene({
            Column {
                Button(modifier = Modifier.focusOnDialogOpen(enabled), onClick = {}) { Text("First") }
                Button(onClick = {}) { Text("Second") }
            }
        }) {
            assertEquals("First", focused()[SemanticsProperties.Text].single().text)
            press(Key.Tab)
            assertEquals("Second", focused()[SemanticsProperties.Text].single().text)
            enabled = false
            draw()
            enabled = true
            draw()
            assertEquals("Second", focused()[SemanticsProperties.Text].single().text)
        }
    }
}

private fun withScene(
    content: @Composable () -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
    test: ImageComposeScene.() -> Unit,
) {
    SwingUtilities.invokeAndWait {
        ImageComposeScene(900, 700, coroutineContext = coroutineContext) { WizardPreview { content() } }.use { scene ->
            scene.draw()
            scene.test()
        }
    }
}

private fun ImageComposeScene.draw() {
    repeat(4) { render().close() }
}

private fun ImageComposeScene.press(key: Key, shift: Boolean = false) {
    sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
    sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
    draw()
}

private fun ImageComposeScene.focused(): SemanticsConfiguration = assertNotNull(
    semanticsOwners.toList().asReversed().firstNotNullOfOrNull { owner ->
        owner.getAllSemanticsNodes(mergingEnabled = true)
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.config
    },
)

private fun ImageComposeScene.enterText(text: String) {
    assertTrue(focused()[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
    draw()
}
