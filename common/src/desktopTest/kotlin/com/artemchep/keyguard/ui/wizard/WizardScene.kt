package com.artemchep.keyguard.ui.wizard

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Renders a settled frame to [folder] and runs [check] while the semantics tree is attached. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun renderScene(
    folder: String,
    name: String,
    width: Int,
    height: Int,
    fontScale: Float,
    rtl: Boolean,
    content: @Composable () -> Unit,
    check: (List<SemanticsNode>) -> Unit,
) {
    ImageComposeScene(
        width = width,
        height = height,
        density = Density(1f, fontScale),
        layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content,
    ).use { scene ->
        scene.render(0).close()
        scene.render(1_000_000_000).use { image ->
            val dir = File(folder).apply { mkdirs() }
            image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                File(dir, "$name.png").writeBytes(data.bytes)
            }
        }
        check(scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) })
    }
}

private fun descendants(node: SemanticsNode): List<SemanticsNode> =
    listOf(node) + node.children.flatMap(::descendants)
