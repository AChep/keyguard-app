package com.artemchep.keyguard.feature.gpgagent.tools.keypicker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.dialog.DialogContent
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class GpgToolsKeyPickerScreenTest {
    @Test
    fun `search actions and selectable rows remain accessible in constrained dialogs`() {
        SwingUtilities.invokeAndWait {
            listOf(
                Variant("phone", 390, 844),
                Variant("short", 390, 400),
                Variant("large-text", 390, 640, fontScale = 1.6f),
                Variant("desktop-dark", 1000, 800, dark = true),
                Variant("signing", 390, 640, multiple = false),
                Variant("empty", 390, 640, count = 0),
            ).forEach(::render)
        }
    }

    private fun render(variant: Variant) {
        var selections = 0
        val state = state(variant) { selections++ }
        ImageComposeScene(
            width = variant.width,
            height = variant.height,
            density = Density(1f, variant.fontScale),
        ) {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MaterialTheme(colorScheme = if (variant.dark) darkColorScheme() else MaterialTheme.colorScheme) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                            DialogContent(
                                title = { Text(if (variant.multiple) "Recipients" else "Signing key") },
                                contentScrollable = false,
                                content = {
                                    GpgToolsKeyPickerBody(
                                        state = state,
                                        multiple = variant.multiple,
                                        hasKeys = state.items.isNotEmpty(),
                                    )
                                },
                                actions = {
                                    TextButton(onClick = {}) { Text("Cancel") }
                                    TextButton(onClick = {}) { Text("Done") }
                                },
                            )
                        }
                    }
                }
            }
        }.use { scene ->
            scene.render(0).close()
            scene.render(1_000_000_000).use { image ->
                val dir = File("build/reports/gpg-tools-key-picker").apply { mkdirs() }
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    File(dir, "${variant.name}.png").writeBytes(data.bytes)
                }
            }
            val nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            assertDialog(variant, nodes)
            assertEquals(if (variant.count > 0) 1 else 0, selections)
        }
    }

    private fun state(variant: Variant, onSelect: () -> Unit) = GpgToolsKeyPickerState(
        query = TextFieldModel(text = "", onChange = {}),
        items = List(variant.count) { index ->
            GpgToolsKeyPickerState.Item(
                key = GpgToolsState.KeyItem(
                    id = index.toString(),
                    title = "Alice — signing and encryption key",
                    description = "0123 4567 89AB CDEF 0123 4567 89AB CDEF 0123 4567 89AB CDEF 0123 4567 89AB CDEF",
                    canSign = true,
                    canDecrypt = true,
                    publicKeyAvailable = true,
                ),
                selected = index == 0,
                onSelect = onSelect,
            )
        },
    )

    private fun assertDialog(variant: Variant, nodes: List<SemanticsNode>) {
        val done = nodes.first { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Done" } == true
        }
        val search = nodes.first { it.config.getOrNull(SemanticsProperties.EditableText) != null }
        listOf(done, search).forEach { node ->
            val bounds = node.boundsInRoot
            assertTrue(bounds.width > 0f && bounds.height > 0f, variant.name)
            assertTrue(bounds.top >= 0 && bounds.bottom <= variant.height, variant.name)
            assertTrue(bounds.left >= 0 && bounds.right <= variant.width, variant.name)
        }
        if (variant.count > 0) {
            val expectedRole = if (variant.multiple) Role.Checkbox else Role.RadioButton
            val row = nodes.first { it.config.getOrNull(SemanticsProperties.Role) == expectedRole }
            assertTrue(!row.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
            assertTrue(row.config[SemanticsActions.OnClick].action!!.invoke())
            val scroll = nodes.first {
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            }
            assertTrue(scroll.boundsInRoot.bottom <= done.boundsInRoot.top, variant.name)
            assertTrue(scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private data class Variant(
        val name: String,
        val width: Int,
        val height: Int,
        val fontScale: Float = 1f,
        val dark: Boolean = false,
        val multiple: Boolean = true,
        val count: Int = 30,
    )
}
