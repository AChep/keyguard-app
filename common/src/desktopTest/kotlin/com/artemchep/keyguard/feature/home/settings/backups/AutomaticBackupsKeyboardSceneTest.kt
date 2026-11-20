@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.semantics.SemanticsProperties
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationEntryImpl
import com.artemchep.keyguard.ui.advanceTimeBy
import com.artemchep.keyguard.ui.draw
import com.artemchep.keyguard.ui.focused
import com.artemchep.keyguard.ui.focusedOrNull
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.mouseClick
import com.artemchep.keyguard.ui.press
import com.artemchep.keyguard.ui.withScene
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Keyboard paths through the Automatic backups settings page. */
class AutomaticBackupsKeyboardSceneTest {
    // Internal, so the scene helpers below the class can reach it.
    internal val entryScope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        entryScope.cancel()
    }

    @Test
    fun enabledPageFocusesItsFirstRowAndRestoresTheRowWhenItComesBack() {
        var shown by mutableStateOf(true)
        withScreen({ settingsState(enabled = true) }, shown = { shown }) {
            // The status card above it is not a row.
            assertEquals(listOf(REPOSITORY), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(listOf(LAST_BACKUP), focusedTexts())
            press(Key.DirectionDown)
            assertEquals(KEEP_SNAPSHOTS, focusedTexts().firstOrNull())
            press(Key.DirectionDown)
            assertEquals(listOf(IMPORT), focusedTexts())

            shown = false
            draw()
            assertNull(focusedOrNull())
            shown = true
            draw()
            assertEquals(listOf(IMPORT), focusedTexts())
        }
    }

    @Test
    fun disabledPageFocusesSetUp() {
        withScreen({ settingsState(enabled = false) }) {
            assertEquals(listOf(SET_UP), focusedTexts())
            assertEquals(Role.Button, focused()[SemanticsProperties.Role])
        }
    }

    @Test
    fun pageFocusesNothingInTouchMode() {
        var enabled by mutableStateOf(true)
        var shown by mutableStateOf(false)
        withScreen({ settingsState(enabled = enabled) }, shown = { shown }) {
            mouseClick(Offset(450f, 600f))
            assertEquals(InputMode.Touch, inputMode)

            shown = true
            advanceTimeBy(1_000L)
            assertNull(focusedOrNull())

            shown = false
            enabled = false
            draw()
            shown = true
            advanceTimeBy(1_000L)
            assertNull(focusedOrNull())
        }
    }
}

private const val REPOSITORY = "Repository metadata"

private const val LAST_BACKUP = "Last backup"

private const val KEEP_SNAPSHOTS = "Keep snapshots"

private const val IMPORT = "Import from backup"

private const val SET_UP = "Set up backups"

private fun settingsState(enabled: Boolean) = AutomaticBackupsSettingsState(
    config = BackupConfig(
        enabled = enabled,
        store = BackupStoreConfig.Local("/backups"),
    ),
    status = BackupStatus(),
    onSetupClick = {},
    onRetentionChange = {},
    onRunNow = {},
    onImport = {},
    onDisableClick = {},
)

/** The page under a navigation entry, the way its route shows it. */
private fun AutomaticBackupsKeyboardSceneTest.withScreen(
    state: () -> AutomaticBackupsSettingsState,
    shown: () -> Boolean = { true },
    test: ImageComposeScene.() -> Unit,
) {
    val entry = NavigationEntryImpl(
        source = "test",
        parent = entryScope,
        id = "automatic_backups",
        route = AutomaticBackupsSettingsRoute,
    )
    withScene({
        CompositionLocalProvider(
            LocalNavigationEntry provides entry,
        ) {
            if (shown()) {
                AutomaticBackupsSettingsContent(
                    state = state(),
                    dateFormatter = AutomaticBackupsPreviewData.dateFormatter,
                )
            }
        }
    }, test = test)
}
