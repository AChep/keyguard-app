@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.ui.wizard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.artemchep.keyguard.feature.export.ExportPreviewData
import com.artemchep.keyguard.feature.export.ExportStep
import com.artemchep.keyguard.feature.export.ExportWizardContent
import com.artemchep.keyguard.feature.export.exportPreviewState
import com.artemchep.keyguard.feature.home.settings.backups.AutomaticBackupsPreviewData
import com.artemchep.keyguard.feature.home.settings.backups.AutomaticBackupsSetupContent
import com.artemchep.keyguard.feature.home.settings.backups.BackupSetupData
import com.artemchep.keyguard.feature.home.settings.backups.BackupSetupStep
import com.artemchep.keyguard.feature.home.settings.backups.automaticBackupsSetupPreviewState
import com.artemchep.keyguard.ui.advanceTimeBy
import com.artemchep.keyguard.ui.draw
import com.artemchep.keyguard.ui.focused
import com.artemchep.keyguard.ui.focusedNode
import com.artemchep.keyguard.ui.focusedOrNull
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.keyboardScene
import com.artemchep.keyguard.ui.mergedNodes
import com.artemchep.keyguard.ui.mouseClick
import com.artemchep.keyguard.ui.press
import com.artemchep.keyguard.ui.withScene
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

/** Keyboard paths through the steps of a wizard. */
class WizardKeyboardSceneTest {
    @Test
    fun stepFocusesItsSelectedChoiceAndArrowsMoveBetweenChoices() {
        val data = AutomaticBackupsPreviewData.setup(store = AutomaticBackupsPreviewData.webDavStore)
        withSetup({ data }) {
            assertEquals(listOf(WEBDAV), focusedTexts())
            assertEquals(true, focused()[SemanticsProperties.Selected])
            press(Key.DirectionDown)
            assertEquals(listOf(S3), focusedTexts())
            press(Key.DirectionUp)
            press(Key.DirectionUp)
            assertEquals(listOf(LOCAL_FOLDER), focusedTexts())
        }
    }

    @Test
    fun arrowsMoveFocusThroughTheStepAndItsBars() {
        val data = AutomaticBackupsPreviewData.setup(store = AutomaticBackupsPreviewData.localStore)
        withSetup({ data }) {
            assertEquals(listOf(LOCAL_FOLDER), focusedTexts())
            repeat(3) { press(Key.DirectionDown) }
            assertEquals(listOf(CHOOSE_FOLDER), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf(CONTINUE), focusedTexts())
            // The bars point at the step, under which the list lies.
            press(Key.DirectionUp)
            assertEquals(listOf(CHOOSE_FOLDER), focusedTexts())
            press(Key.DirectionUp)
            assertEquals(listOf(S3), focusedTexts())

            repeat(2) { press(Key.DirectionUp) }
            assertEquals(listOf(LOCAL_FOLDER), focusedTexts())
            press(Key.DirectionUp)
            assertEquals(listOf(BACK), focused()[SemanticsProperties.ContentDescription])
            press(Key.DirectionDown)
            assertEquals(listOf(LOCAL_FOLDER), focusedTexts())
        }
    }

    @Test
    fun stepFocusesNothingInTouchMode() {
        var data by mutableStateOf(AutomaticBackupsPreviewData.setup())
        var shown by mutableStateOf(false)
        withSetup({ data }, shown = { shown }) {
            mouseClick(Offset(450f, 600f))
            assertEquals(InputMode.Touch, inputMode)

            shown = true
            advanceTimeBy(1_000L)
            assertNull(focusedOrNull())

            data = data.copy(step = BackupSetupStep.Review)
            advanceTimeBy(1_000L)
            assertNull(focusedOrNull())
        }
    }

    @Test
    fun stepWithoutControlsFocusesThePrimaryAction() {
        withSetup({ AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Review) }) {
            assertEquals(listOf(ENABLE), focusedTexts())
            assertEquals(Role.Button, focused()[SemanticsProperties.Role])
        }
    }

    @Test
    fun continueFocusesTheFirstControlOfTheNextStep() {
        var data by mutableStateOf(AutomaticBackupsPreviewData.setup())
        val onContinue = {
            data = data.copy(step = BackupSetupStep.entries[data.step.ordinal + 1])
        }
        withSetup({ data }, onContinue = onContinue) {
            assertEquals(listOf(LOCAL_FOLDER), focusedTexts())
            repeat(MAX_TABS) {
                if (focusedTexts() != listOf(CONTINUE)) press(Key.Tab)
            }
            assertEquals(listOf(CONTINUE), focusedTexts())

            press(Key.Enter)
            assertEquals(BackupSetupStep.Protection, data.step)
            advanceTimeBy(1_000L)
            assertEquals(textFields().first().id, focusedNode().id)
        }
    }

    @Test
    fun protectionNextSkipsTheVisibilityToggleAndDoneContinues() {
        var continued = 0
        val data = AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Protection, password = "secret")
        withSetup({ data }, onContinue = { continued++ }) {
            val (password, confirmation) = textFields()
            assertEquals(password.id, focusedNode().id)

            press(Key.Enter)
            assertEquals(confirmation.id, focusedNode().id)
            assertEquals(0, continued)

            press(Key.Enter)
            assertEquals(1, continued)
        }
    }

    @Test
    fun protectionArrowsMoveTheCaretInsideThePassword() {
        val data = AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Protection, password = "secret")
        withSetup({ data }) {
            val password = textFields().first()
            assertEquals(password.id, focusedNode().id)
            assertEquals(TextRange(6), focused()[SemanticsProperties.TextSelectionRange])

            press(Key.DirectionLeft)
            press(Key.DirectionLeft)
            assertEquals(password.id, focusedNode().id)
            assertEquals(TextRange(4), focused()[SemanticsProperties.TextSelectionRange])

            press(Key.DirectionRight)
            assertEquals(password.id, focusedNode().id)
            assertEquals(TextRange(5), focused()[SemanticsProperties.TextSelectionRange])
        }
    }

    @Test
    fun protectionFocusesThePasswordUnderATallHeader() {
        val data = AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Protection, password = "secret")
        // Large text makes the header tall, and the visibility
        // toggle starts above the password inside the same row.
        withNarrowLargeTextScene({
            AutomaticBackupsSetupContent(automaticBackupsSetupPreviewState(data))
        }) {
            assertEquals(textFields().first().id, focusedNode().id)
        }
    }

    @Test
    fun exportPasswordNextSkipsTheTrailingButtonsAndDoneContinues() {
        var continued = 0
        val data = ExportPreviewData(step = ExportStep.Password, password = "secret", confirmation = "secret")
        withScene({
            val state = exportPreviewState(data)
            val wizard = state.wizardFlow.value.copy(onContinue = { continued++ })
            ExportWizardContent(
                title = "",
                state = state.copy(wizardFlow = MutableStateFlow(wizard)),
            )
        }) {
            val (password, confirmation) = textFields()
            assertEquals(password.id, focusedNode().id)

            press(Key.Enter)
            assertEquals(confirmation.id, focusedNode().id)
            assertEquals(0, continued)

            press(Key.Enter)
            assertEquals(1, continued)
        }
    }
}

private const val MAX_TABS = 12

private const val LOCAL_FOLDER = "Local folder"

private const val WEBDAV = "WebDAV server"

private const val S3 = "S3-compatible storage"

private const val CHOOSE_FOLDER = "Choose folder…"

private const val BACK = "Back"

private const val CONTINUE = "Continue"

private const val ENABLE = "Enable backups"

/** The text fields of the scene, from top to bottom. */
private fun ImageComposeScene.textFields(): List<SemanticsNode> = mergedNodes()
    .filter { SemanticsProperties.EditableText in it.config }
    .sortedBy { it.boundsInRoot.top }
    .also { assertTrue(it.isNotEmpty(), "No text fields") }

/** Like [withScene], on a phone-sized scene with large text. */
private fun withNarrowLargeTextScene(
    content: @Composable () -> Unit,
    test: ImageComposeScene.() -> Unit,
) {
    SwingUtilities.invokeAndWait {
        keyboardScene(390, 2400, density = Density(1f, fontScale = 2f)) {
            WizardPreview { content() }
        }.use { scene ->
            scene.draw()
            scene.test()
        }
    }
}

/** The backups setup wizard, with [onContinue] for its primary action. */
private fun withSetup(
    data: () -> BackupSetupData,
    shown: () -> Boolean = { true },
    onContinue: () -> Unit = {},
    test: ImageComposeScene.() -> Unit,
) {
    withScene({
        if (shown()) {
            val setup = data()
            val state = automaticBackupsSetupPreviewState(setup)
                .copy(onContinue = onContinue.takeIf { setup.canContinue })
            AutomaticBackupsSetupContent(state)
        }
    }, test = test)
}
