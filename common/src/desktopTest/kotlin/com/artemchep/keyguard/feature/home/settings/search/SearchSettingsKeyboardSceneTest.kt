@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.settings.search

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.settings.ActionRow
import com.artemchep.keyguard.ui.focused
import com.artemchep.keyguard.ui.focusedTexts
import com.artemchep.keyguard.ui.inputMode
import com.artemchep.keyguard.ui.mergedNodes
import com.artemchep.keyguard.ui.mouseClick
import com.artemchep.keyguard.ui.press
import com.artemchep.keyguard.ui.withScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Keyboard paths of the Settings search. */
class SearchSettingsKeyboardSceneTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun downAfterAClickOnTheFieldSwitchesToKeyboardMode() {
        val dispatcher = UnconfinedTestDispatcher()
        withScene({
            val state = SearchSettingsState(
                query = TextFieldModel(text = "ab", onChange = {}),
                items = listOf(
                    SearchSettingsState.Item.Settings(
                        key = "Alpha",
                        score = 1f,
                        content = { ActionRow("Alpha") },
                    ),
                ),
            )
            SearchSettingsScreenContent(state)
        }, dispatcher) {
            val field = mergedNodes().first { node ->
                node.config.getOrNull(SemanticsProperties.EditableText) != null
            }
            mouseClick(field.boundsInRoot.center)
            assertEquals(InputMode.Touch, inputMode)
            assertEquals("ab", focused()[SemanticsProperties.EditableText].text)

            // Enter on the row then works like after a Tab.
            press(Key.DirectionDown)
            assertEquals(listOf("Alpha"), focusedTexts())
            assertEquals(InputMode.Keyboard, inputMode)
        }
    }
}
