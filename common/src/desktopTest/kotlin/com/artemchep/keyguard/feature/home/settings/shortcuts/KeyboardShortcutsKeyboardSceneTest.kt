@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
import com.artemchep.keyguard.common.model.GlobalShortcutKey
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutStatus
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationEntryImpl
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.ui.draw
import com.artemchep.keyguard.ui.focused
import com.artemchep.keyguard.ui.focusedOrNull
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.press
import com.artemchep.keyguard.ui.withScene
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Keyboard paths through the Keyboard shortcuts settings page. */
class KeyboardShortcutsKeyboardSceneTest {
    // Internal, so the scene helpers below the class can reach it.
    internal val entryScope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        entryScope.cancel()
    }

    @Test
    fun pageFocusesItsFirstRowAndArrowsReachTheRowButtons() {
        withScreen({ shortcutsState(busy = false) }) {
            assertSwitchRowFocused(QUICK_SEARCH)

            // The binding comes first, though Reset is nearer to the
            // middle of the row.
            press(Key.DirectionDown)
            assertBindingFocused(QUICK_SEARCH)
            // The row has a binding of its own, so it can be reset.
            press(Key.DirectionRight)
            assertEquals(listOf("Reset"), focusedTexts())

            press(Key.DirectionDown)
            assertSwitchRowFocused(LOCK_VAULT)
        }
    }

    @Test
    fun pageRestoresTheFocusedRowWhenBusyEnds() {
        var busy by mutableStateOf(false)
        withScreen({ shortcutsState(busy = busy) }) {
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            assertSwitchRowFocused(LOCK_VAULT)

            // Busy disables every control.
            busy = true
            draw()
            assertNull(focusedOrNull())

            busy = false
            draw()
            assertSwitchRowFocused(LOCK_VAULT)
        }
    }
}

private const val QUICK_SEARCH = "Quick search"

private const val LOCK_VAULT = "Lock vault"

private fun ImageComposeScene.assertSwitchRowFocused(title: String) {
    assertEquals(title, focusedTexts().firstOrNull())
    assertEquals(Role.Switch, focused()[SemanticsProperties.Role])
}

private fun ImageComposeScene.assertBindingFocused(title: String) {
    assertEquals(listOf("$title shortcut"), focused()[SemanticsProperties.ContentDescription])
}

private fun shortcutsState(busy: Boolean): Loadable<KeyboardShortcutsSettingsState> {
    fun item(
        action: GlobalShortcutAction,
        binding: GlobalShortcutBinding?,
    ) = KeyboardShortcutsSettingsState.Item(
        action = action,
        config = GlobalShortcutConfig(binding = binding),
        status = GlobalShortcutStatus.Active,
        error = null,
        onEdit = {},
        onEnabledChange = {},
        onReset = {},
    )
    return Loadable.Ok(
        KeyboardShortcutsSettingsState(
            items = listOf(
                item(
                    action = GlobalShortcutAction.QuickSearch,
                    binding = GlobalShortcutBinding(GlobalShortcutKey.K, ctrl = true, shift = true),
                ),
                item(
                    action = GlobalShortcutAction.LockVault,
                    binding = null,
                ),
            ),
            busy = busy,
        ),
    )
}

private object UnusedRoute : Route {
    @Composable
    override fun Content() = Unit
}

/** The page under a navigation entry, the way its route shows it. */
private fun KeyboardShortcutsKeyboardSceneTest.withScreen(
    state: () -> Loadable<KeyboardShortcutsSettingsState>,
    test: ImageComposeScene.() -> Unit,
) {
    val entry = NavigationEntryImpl(
        source = "test",
        parent = entryScope,
        id = "shortcuts",
        route = UnusedRoute,
    )
    withScene({
        CompositionLocalProvider(
            LocalNavigationEntry provides entry,
        ) {
            KeyboardShortcutsSettingsContent(state())
        }
    }, test = test)
}
