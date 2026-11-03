@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import com.artemchep.keyguard.ui.wizard.WizardPreview
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

class AttachmentPreviewJsonScreenTest {
    private var frameTime = 0L

    @Test
    fun `tree exposes types counts and expansion in compact wide and large RTL layouts`() {
        val content = content(
            """{"account":{"enabled":true,"quota":12.5,"expires":null},"empty":[],"token":"quoted\n\"value\""}""",
        )
        val variants = listOf(
            Variant("compact", 390),
            Variant("wide-dark", 960, dark = true),
            Variant("large-rtl", 390, fontScale = 1.6f, rtl = true),
        )
        SwingUtilities.invokeAndWait {
            variants.forEach { variant ->
                val state = AttachmentPreviewJsonViewState(content.preview)
                ImageComposeScene(
                    width = variant.width,
                    height = 1100,
                    density = Density(1f, variant.fontScale),
                    layoutDirection = if (variant.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WizardPreview(dark = variant.dark) {
                        AttachmentPreviewJsonContent(content, state, false, PaddingValues())
                    }
                }.use { scene ->
                    settle(scene)
                    val root = row(scene, "$")
                    assertTrue(description(root).contains("Object · 3 properties"))
                    assertEquals("Expanded", root.config[SemanticsProperties.StateDescription])
                    val account = row(scene, "account")
                    assertEquals("Collapsed", account.config[SemanticsProperties.StateDescription])
                    val empty = row(scene, "empty")
                    assertTrue(description(empty).contains("Array · 0 items"))
                    assertNull(empty.config.getOrNull(SemanticsProperties.Disabled))
                    assertNull(empty.config.getOrNull(SemanticsActions.OnClick))
                    assertTrue(description(row(scene, "token")).contains("String"))
                    assertTrue(account.config[SemanticsActions.OnClick].action!!.invoke())
                    settle(scene)
                    assertTrue(description(row(scene, "enabled")).contains("Boolean · true"))
                    assertTrue(description(row(scene, "quota")).contains("Number · 12.5"))
                    assertTrue(description(row(scene, "expires")).contains("Null · null"))
                    rows(scene).forEach { node ->
                        assertTrue(node.boundsInRoot.left >= 0f)
                        assertTrue(node.boundsInRoot.right <= variant.width)
                    }
                    save(scene, variant.name)
                }
            }
        }
    }

    @Test
    fun `view changes preserve expansion and each scroll position until the parsed tree changes`() {
        val source = "{\n\"account\": {\"enabled\":true},\n" +
            (1..60).joinToString(",\n") { "\"field$it\":\"${"long value ".repeat(12)}\"" } + "\n}"
        val current = mutableStateOf(content(source))
        val dark = mutableStateOf(false)
        lateinit var state: AttachmentPreviewJsonViewState
        SwingUtilities.invokeAndWait {
            ImageComposeScene(width = 390, height = 700) {
                state = requireNotNull(rememberAttachmentPreviewJsonViewState(current.value.preview))
                WizardPreview(dark = dark.value) {
                    AttachmentPreviewJsonContent(current.value, state, false, PaddingValues())
                }
            }.use { scene ->
                settle(scene)
                row(scene, "account").config[SemanticsActions.OnClick].action!!.invoke()
                settle(scene)
                val initialState = state
                val expandedIds = state.expandedIds
                state.treeScrollState.requestScrollToItem(12)
                settle(scene)
                val treeIndex = state.treeScrollState.firstVisibleItemIndex
                assertTrue(treeIndex > 0)

                clickTab(scene, "Source")
                settle(scene)
                state.sourceScrollState.requestScrollToItem(24)
                settle(scene)
                val sourceIndex = state.sourceScrollState.firstVisibleItemIndex
                assertTrue(sourceIndex > 0)
                state.sourceHorizontalScrollState.dispatchRawDelta(40f)
                val horizontalOffset = state.sourceHorizontalScrollState.value
                assertTrue(horizontalOffset > 0)
                dark.value = true
                current.value = current.value.copy(
                    code = current.value.code.copy(
                        annotatedString = AnnotatedString(source, SpanStyle(fontWeight = FontWeight.Bold)),
                    ),
                )
                settle(scene)
                assertSame(initialState, state)
                assertEquals(expandedIds, state.expandedIds)
                assertEquals(sourceIndex, state.sourceScrollState.firstVisibleItemIndex)

                clickTab(scene, "Tree")
                settle(scene)
                assertEquals(treeIndex, state.treeScrollState.firstVisibleItemIndex)
                clickTab(scene, "Source")
                settle(scene)
                assertEquals(sourceIndex, state.sourceScrollState.firstVisibleItemIndex)
                assertEquals(horizontalOffset, state.sourceHorizontalScrollState.value)

                current.value = content(source)
                settle(scene)
                assertNotSame(initialState, state)
                assertEquals(AttachmentPreviewJsonMode.Tree, state.mode)
                assertEquals(setOf(current.value.preview.tree!!.rootId), state.expandedIds)
                assertEquals(0, state.treeScrollState.firstVisibleItemIndex)
                assertEquals(0, state.sourceScrollState.firstVisibleItemIndex)
            }
        }
    }

    @Test
    fun `invalid JSON displays its source and reason without a tree tab`() {
        val content = content("{ invalid JSON }")
        SwingUtilities.invokeAndWait {
            val state = AttachmentPreviewJsonViewState(content.preview)
            ImageComposeScene(width = 390, height = 600) {
                WizardPreview {
                    AttachmentPreviewJsonContent(content, state, false, PaddingValues())
                }
            }.use { scene ->
                settle(scene)
                assertEquals(AttachmentPreviewJsonMode.Source, state.mode)
                val texts = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                assertTrue(texts.any { it.text == content.text })
                assertTrue(texts.any { it.text.contains("valid JSON", ignoreCase = true) })
                assertFalse(texts.any { it.text == "Tree" })
                save(scene, "invalid")
            }
        }
    }

    private fun content(text: String) = AttachmentPreviewContent.Json(
        code = AttachmentPreviewCode(
            text = text,
            lineIndex = AttachmentPreviewLineIndex.of(text),
            syntaxLanguage = null,
            annotatedString = AnnotatedString(text),
        ),
        preview = parseAttachmentPreviewJson(text, text.encodeToByteArray().size),
        onCopy = {},
    )

    private fun clickTab(scene: ImageComposeScene, label: String) {
        val tab = nodes(scene).first {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                it.config.getOrNull(SemanticsActions.OnClick) != null
        }
        assertTrue(tab.config[SemanticsActions.OnClick].action!!.invoke())
    }

    private fun settle(scene: ImageComposeScene) {
        repeat(5) {
            frameTime += 200_000_000L
            scene.render(frameTime).close()
        }
    }

    private fun save(scene: ImageComposeScene, name: String) {
        frameTime += 200_000_000L
        scene.render(frameTime).use { image ->
            val folder = File("build/reports/attachment-json").apply { mkdirs() }
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                File(folder, "$name.png").writeBytes(data.bytes)
            }
        }
    }

    private fun row(scene: ImageComposeScene, key: String) =
        rows(scene).single { description(it).startsWith("$key. ") }

    private fun rows(scene: ImageComposeScene) =
        nodes(scene).filter { it.config.getOrNull(SemanticsProperties.ContentDescription) != null }

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private data class Variant(
        val name: String,
        val width: Int,
        val dark: Boolean = false,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
    )
}

private fun description(node: SemanticsNode) =
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
