package com.artemchep.keyguard.feature.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.mouseClick
import com.artemchep.keyguard.ui.nodeWithText
import com.artemchep.keyguard.ui.nodeWithTextOrNull
import com.artemchep.keyguard.ui.texts
import com.artemchep.keyguard.ui.withScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentApprovalDetailsTest {
    private val caller = "Zen.app • /Applications/Zen.app • zen • pid 1105 • /Applications/Zen.app/Contents/MacOS/zen"
    private val extension = "Extension: moz-extension://0d2c8f1e-5b7a-4c3d-9e6f-a1b2c3d4e5f6 of the browser"

    /** Shows an approval window of the width of the real one with the [details]. */
    private fun withDetails(
        details: List<String>,
        test: ImageComposeScene.() -> Unit,
    ) = withScene(
        content = {
            val guard = remember { AgentApprovalInputGuard() }
            ProvideAgentApprovalInputGuard(guard = guard, requestId = "request") {
                Box(
                    modifier = Modifier
                        .width(320.dp),
                ) {
                    AgentApprovalDialog(
                        icon = {},
                        title = "Title",
                        message = { AnnotatedString("Message") },
                        callerInfo = null,
                        details = details,
                        expiresAt = null,
                        cancelText = "Deny",
                        confirmText = "Approve",
                        onCancel = {},
                        onConfirm = {},
                    ) {
                    }
                }
            }
        },
        test = test,
    )

    @Test
    fun `details that do not fit collapse, and expand on click`() {
        val summary = "$caller • $extension"
        withDetails(listOf(caller, extension)) {
            // One block of text, cut off.
            val collapsed = nodeWithText(summary)
            assertNull(nodeWithTextOrNull(extension))

            mouseClick(collapsed.boundsInRoot.center)
            // Each detail in full, on its own lines.
            val expanded = nodeWithText(extension)
            assertEquals(listOf(caller, extension), expanded.texts())

            mouseClick(expanded.boundsInRoot.center)
            assertEquals(listOf(summary), nodeWithText(summary).texts())
        }
    }

    @Test
    fun `details that fit do not expand`() {
        val detail = "zen • pid 1105"
        withDetails(listOf(detail)) {
            val node = nodeWithText(detail)
            assertTrue(SemanticsProperties.Disabled in node.config)
            mouseClick(node.boundsInRoot.center)
            assertEquals(listOf(detail), nodeWithText(detail).texts())
        }
    }
}
