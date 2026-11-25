package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.artemchep.keyguard.ui.advanceTimeBy
import com.artemchep.keyguard.ui.draw
import com.artemchep.keyguard.ui.mouseClick
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.nodeWithTextOrNull
import com.artemchep.keyguard.ui.texts
import com.artemchep.keyguard.ui.withScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentApprovalItemTest {
    private val leading: @Composable RowScope.() -> Unit = {
        Icon(Icons.Outlined.Key, contentDescription = null)
    }

    @Test
    fun `an item with nothing hidden does not expand`() {
        var color: Color? = null
        var itemColor: Color? = null
        withScene(
            content = {
                color = LocalContentColor.current
                AgentApprovalItem(
                    leading = {
                        itemColor = LocalContentColor.current
                        leading()
                    },
                    title = "Run",
                    text = "Work • Personal",
                )
            },
        ) {
            // Not dimmed as a disabled item.
            assertEquals(color, itemColor)

            val node = nodeWithText("Run")
            assertTrue(SemanticsProperties.Disabled !in node.config)
            assertNull(node.config.getOrNull(SemanticsActions.OnClick))
            assertNull(node.config.getOrNull(SemanticsActions.Expand))
            assertEquals(listOf("Run", "Work • Personal"), node.texts())
        }
    }

    @Test
    fun `a click shows and hides the details`() {
        withScene(
            content = {
                AgentApprovalItem(
                    leading = leading,
                    title = "Run",
                    text = "Work • Personal",
                    details = {
                        Text("echo hi")
                    },
                )
            },
        ) {
            assertNull(nodeWithTextOrNull("echo hi"))
            assertNull(nodeWithText("Run").config.getOrNull(SemanticsActions.Collapse))

            mouseClick(nodeWithText("Run").boundsInRoot.center)
            assertNotNull(nodeWithTextOrNull("echo hi"))
            assertNull(nodeWithText("Run").config.getOrNull(SemanticsActions.Expand))

            mouseClick(nodeWithText("Run").boundsInRoot.center)
            // The details stay in the tree until they shrink out.
            advanceTimeBy(1_000)
            assertNull(nodeWithTextOrNull("echo hi"))
        }
    }

    @Test
    fun `accessibility actions expand and collapse the details`() {
        withScene(
            content = {
                AgentApprovalItem(
                    leading = leading,
                    title = "Run",
                    details = {
                        Text("echo hi")
                    },
                )
            },
        ) {
            val expand = assertNotNull(nodeWithText("Run").config.getOrNull(SemanticsActions.Expand))
            assertTrue(assertNotNull(expand.action).invoke())
            draw()
            assertNotNull(nodeWithTextOrNull("echo hi"))

            val collapse = assertNotNull(nodeWithText("Run").config.getOrNull(SemanticsActions.Collapse))
            assertTrue(assertNotNull(collapse.action).invoke())
            advanceTimeBy(1_000)
            assertNull(nodeWithTextOrNull("echo hi"))
        }
    }

    @Test
    fun `a long text expands on click`() {
        val text = List(80) { "word$it" }.joinToString(separator = " ")
        withScene(
            content = {
                AgentApprovalItem(
                    leading = leading,
                    title = "GitHub",
                    text = text,
                )
            },
        ) {
            val collapsed = nodeWithText("GitHub")
            assertNotNull(collapsed.config.getOrNull(SemanticsActions.OnClick))
            val bounds = collapsed.boundsInRoot

            mouseClick(bounds.center)
            advanceTimeBy(1_000)
            val expanded = nodeWithText("GitHub")
            assertEquals(listOf("GitHub", text), expanded.texts())
            assertTrue(expanded.boundsInRoot.height > bounds.height)
        }
    }
}
