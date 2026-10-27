package com.artemchep.keyguard.ui.diff

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.artemchep.keyguard.ui.wizard.WizardPreview
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class TextDiffScreenTest {
    @Test
    fun `diff markers and context work in narrow wide dark and large RTL layouts`() = runBlocking {
        val before = (1..25).joinToString("\n", postfix = "\n") { "# setting $it" }
        val after = before.replace("# setting 4", "export SSH_AUTH_SOCK=/some/long/path/to/keyguard/ssh-agent.sock")
            .replace("# setting 23", "export GNUPGHOME=~/.keyguard/gnupg")
        val model = buildTextDiff(before, after)
        val variants = listOf(
            Variant("narrow", 390),
            Variant("wide-dark", 900, dark = true),
            Variant("large-rtl", 390, scale = 1.6f, rtl = true),
        )
        SwingUtilities.invokeAndWait {
            variants.forEach { variant ->
                ImageComposeScene(
                    width = variant.width,
                    height = 1000,
                    density = Density(1f, variant.scale),
                    layoutDirection = if (variant.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WizardPreview(dark = variant.dark) {
                        TextDiff(
                            model,
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        )
                    }
                }.use { scene ->
                    scene.render(0).close()
                    save(scene, variant.name)
                    val nodes = nodes(scene)
                    assertTrue(nodes.any { text(it).contains("@@ -1,7 +1,7 @@") })
                    assertFalse(nodes.any { text(it).contains("# setting 15") })
                    listOf("Added line" to "+", "Removed line" to "-").forEach { (label, prefix) ->
                        val row = nodes.first { it.config.getOrNull(SemanticsProperties.StateDescription) == label }
                        assertTrue(text(row).startsWith(prefix))
                        assertTrue(row.boundsInRoot.height > 0)
                        assertTrue(row.boundsInRoot.left >= 0 && row.boundsInRoot.right <= variant.width)
                    }
                }
            }
        }
    }

    @Test
    fun `line numbers track both files across separated insertions removals and replacements`() = runBlocking {
        val before = (1..40).joinToString("\n", postfix = "\n") { "settingline$it" }
        val after = "inserted\n" + before.replace("settingline25\n", "")
            .replace("settingline38\n", "replacement\n")
        val model = buildTextDiff(before, after)
        SwingUtilities.invokeAndWait {
            ImageComposeScene(width = 900, height = 1600) {
                WizardPreview { TextDiff(model) }
            }.use { scene ->
                scene.render(0).close()
                val rows = nodes(scene).filter {
                    it.config.getOrNull(SemanticsProperties.ContentDescription) != null && text(it).isNotEmpty()
                }
                val expected = mapOf(
                    "inserted" to "New line 1",
                    "settingline1" to "Original line 1, New line 2",
                    "settingline22" to "Original line 22, New line 23",
                    "settingline25" to "Original line 25",
                    "settingline26" to "Original line 26, New line 26",
                    "settingline38" to "Original line 38",
                    "replacement" to "New line 38",
                    "settingline39" to "Original line 39, New line 39",
                )
                expected.forEach { (content, numbers) ->
                    val row = rows.single {
                        it.config.getOrNull(SemanticsProperties.Text)?.lastOrNull()?.text == content
                    }
                    assertEquals(listOf(numbers), row.config[SemanticsProperties.ContentDescription])
                    // The gutters are separate from the selectable source text.
                    assertEquals(listOf(row.config[SemanticsProperties.Text].first().text, content),
                        row.config[SemanticsProperties.Text].map { it.text })
                }
                save(scene, "line-numbers")
            }
        }
    }

    @Test
    fun `line endings empty files and removal have explicit visible states`() = runBlocking {
        val cases = listOf(
            Triple("line-endings", buildTextDiff("line\r\n", "line\n"), "Line endings changed"),
            Triple("no-final-newline", buildTextDiff("line\n", "line"), "No newline at end of file"),
            Triple("empty-created", buildTextDiff(null, ""), "Create an empty file."),
            Triple("empty-removed", buildTextDiff("", null), "Remove the empty file."),
            Triple("removed", buildTextDiff("removed\n", null), "removed"),
            Triple("no-changes", buildTextDiff("same", "same"), "No text changes."),
        )
        SwingUtilities.invokeAndWait {
            cases.forEach { (name, model, expected) ->
                ImageComposeScene(width = 390, height = 600) {
                    WizardPreview { TextDiff(model) }
                }.use { scene ->
                    scene.render(0).close()
                    save(scene, name)
                    assertTrue(nodes(scene).any { text(it).contains(expected) })
                }
            }
        }
    }

    @Test
    fun `refresh immediately replaces old diff and patch uses the latest inputs`() {
        val after = mutableStateOf("first value\n")
        var patch: String? = null
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(width = 390, height = 700) {
                val result = rememberTextDiff("file", "base\n", after.value)
                patch = result?.patch
                WizardPreview {
                    TextDiff(result?.model)
                }
            }
        }
        try {
            awaitText(scene, "first value", 1)
            SwingUtilities.invokeAndWait {
                after.value = "second value\n"
                scene.render(101_000_000_000L).close()
                scene.render(102_000_000_000L).close()
                assertFalse(nodes(scene).any { text(it).contains("first value") })
            }
            awaitText(scene, "second value", 103)
            assertTrue(patch!!.contains("+second value\n"))
            assertFalse(patch!!.contains("first value"))
        } finally {
            SwingUtilities.invokeAndWait { scene.close() }
        }
    }

    private fun awaitText(scene: ImageComposeScene, expected: String, firstFrame: Int) {
        for (frame in firstFrame until firstFrame + 100) {
            var ready = false
            SwingUtilities.invokeAndWait {
                scene.render(frame * 1_000_000_000L).close()
                ready = nodes(scene).any { text(it).contains(expected) }
            }
            if (ready) return
            Thread.sleep(20)
        }
        error("Diff did not display $expected")
    }

    private fun save(scene: ImageComposeScene, name: String) {
        scene.render(1_000_000_000).use { image ->
            val folder = File("build/reports/text-diff").apply { mkdirs() }
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                File(folder, "$name.png").writeBytes(data.bytes)
            }
        }
    }

    private fun text(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private data class Variant(
        val name: String,
        val width: Int,
        val dark: Boolean = false,
        val scale: Float = 1f,
        val rtl: Boolean = false,
    )
}
