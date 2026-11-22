package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.artemchep.keyguard.ui.mouseClick
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.texts
import com.artemchep.keyguard.ui.withScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentApprovalChoiceListTest {
    private val items = listOf(
        AgentApprovalChoiceItem(title = "GitHub", text = "artem • Personal"),
        AgentApprovalChoiceItem(title = "GitHub (work)", text = "artem-work • Company"),
    )

    @Test
    fun `clicking a row selects it`() {
        var selected by mutableIntStateOf(0)
        val selections = mutableListOf<Int>()
        withScene(
            content = {
                Column {
                    AgentApprovalChoiceList(
                        items = items,
                        selected = selected,
                        onSelect = { index ->
                            selections += index
                            selected = index
                        },
                    )
                }
            },
        ) {
            val work = nodeWithText("GitHub (work)")
            assertEquals(Role.RadioButton, work.config.getOrNull(SemanticsProperties.Role))
            assertEquals(false, work.config.getOrNull(SemanticsProperties.Selected))
            assertEquals(true, nodeWithText("GitHub").config.getOrNull(SemanticsProperties.Selected))

            mouseClick(work.boundsInRoot.center)
            assertEquals(listOf(1), selections)
            assertEquals(true, nodeWithText("GitHub (work)").config.getOrNull(SemanticsProperties.Selected))
            assertEquals(false, nodeWithText("GitHub").config.getOrNull(SemanticsProperties.Selected))
        }
    }

    @Test
    fun `rows without onSelect do not select`() {
        withScene(
            content = {
                Column {
                    AgentApprovalChoiceList(
                        items = items,
                        selected = 0,
                        onSelect = null,
                    )
                }
            },
        ) {
            val work = nodeWithText("GitHub (work)")
            assertTrue(SemanticsProperties.Disabled in work.config)
            // Nothing to call: the click must not fail.
            mouseClick(work.boundsInRoot.center)
            assertEquals(false, nodeWithText("GitHub (work)").config.getOrNull(SemanticsProperties.Selected))
        }
    }

    @Test
    fun `one item is a plain row`() {
        withScene(
            content = {
                Column {
                    AgentApprovalChoiceList(
                        items = items.take(1),
                        selected = 0,
                        onSelect = {},
                    )
                }
            },
        ) {
            val node = nodeWithText("GitHub")
            assertEquals(listOf("GitHub", "artem • Personal"), node.texts())
            assertNull(node.config.getOrNull(SemanticsProperties.Role))
            assertNull(node.config.getOrNull(SemanticsProperties.Selected))
        }
    }
}
