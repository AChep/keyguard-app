package com.artemchep.keyguard.feature.agent.help

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import com.artemchep.keyguard.ui.DialogPopup
import com.artemchep.keyguard.ui.diff.PreparedTextDiff
import com.artemchep.keyguard.ui.diff.buildTextDiff
import com.artemchep.keyguard.ui.screenMaxWidth
import com.artemchep.keyguard.ui.wizard.WizardPreview
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class AgentSetupFileDiffDialogTest {
    @Test
    fun `long diffs scroll while file identity copy and close remain available`() = runBlocking {
        val edit = AgentSetupEdit("/home/alex/.config/powershell/Microsoft.PowerShell_profile.ps1", null,
            (1..100).joinToString("\n", postfix = "\n") { "# Configuration line $it" })
        val model = buildTextDiff(edit.before, edit.after)
        val diff = PreparedTextDiff(model, model.patch(edit.path))
        SwingUtilities.invokeAndWait {
            listOf(390 to false, 1024 to true).forEach { (width, dark) ->
                var copied: String? = null
                var closed = false
                ImageComposeScene(width = width, height = 844, density = Density(1f, if (dark) 1f else 1.6f),
                    layoutDirection = if (dark) LayoutDirection.Ltr else LayoutDirection.Rtl,
                ) {
                    WizardPreview(dark = dark) {
                        DialogPopup(onDismissRequest = { closed = true }, maxWidth = screenMaxWidth) {
                            AgentSetupFileDiffDialogContent(edit, diff,
                                onCopy = { copied = diff.patch }, onDismiss = { closed = true })
                        }
                    }
                }.use { scene ->
                    scene.render(0).close()
                    scene.render(1_000_000_000L).close()
                    val nodes = nodes(scene)
                    assertTrue(nodes.any { text(it) == edit.path })
                    val copy = nodes.first { text(it) == "Copy diff" }
                    val close = nodes.first { text(it) == "Close" }
                    listOf(copy, close).forEach { node ->
                        val bounds = node.boundsInRoot
                        assertTrue(bounds.height > 0 && bounds.top >= 0 && bounds.bottom <= 844)
                        assertTrue(bounds.left >= 0 && bounds.right <= width)
                    }
                    assertTrue(copy.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
                    assertEquals(diff.patch, copied)
                    val scroll = nodes.first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                    assertTrue(scroll.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 400f) == true)
                    scene.render(2_000_000_000L).close()
                    scene.render(3_000_000_000L).use { image ->
                        val file = File("build/reports/agent-setup/diff-dialog-$width.png")
                        file.parentFile.mkdirs()
                        image.encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) }
                    }
                    assertTrue(close.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
                    assertTrue(closed)
                }
            }
        }
    }

    @Test
    fun `automatic setup entry follows the agent switch and retains removal`() {
        SwingUtilities.invokeAndWait {
            listOf(false, true).forEach { enabled ->
                ImageComposeScene(width = 390, height = 700) {
                    WizardPreview {
                        AgentSetupIntro(
                            available = true, enabled = enabled, disabledMessage = "Enable the agent first.",
                            onConfigure = {}, onUndo = {},
                        )
                    }
                }.use { scene ->
                    scene.render(0).close()
                    val nodes = nodes(scene)
                    val start = nodes.first { text(it) == "Configure automatically" }
                    assertEquals(!enabled, start.config.contains(SemanticsProperties.Disabled))
                    val undo = nodes.first {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)
                            ?.contains("Undo configuration") == true
                    }
                    assertTrue(nodes.none { text(it) == "Undo configuration" })
                    assertTrue(!undo.config.contains(SemanticsProperties.Disabled))
                    assertTrue(undo.boundsInRoot.width < start.boundsInRoot.width)
                    assertTrue(undo.boundsInRoot.right <= start.boundsInRoot.right)
                    assertTrue(undo.boundsInRoot.left > start.boundsInRoot.center.x)
                    val title = nodes.first { text(it) == "Automatic configuration" }
                    assertTrue(undo.boundsInRoot.bottom <= title.boundsInRoot.top)
                    scene.render(1_000_000_000L).use { image ->
                        val file = File("build/reports/agent-setup/entry-$enabled.png")
                        file.parentFile.mkdirs()
                        image.encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) }
                    }
                    if (!enabled) assertTrue(nodes.any { text(it) == "Enable the agent first." })
                }
            }
        }
    }

    private fun text(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)
}
