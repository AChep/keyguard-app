@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.wizard.WizardPreview
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingBadgeScreenTest {
    @Test
    fun badgeDrawsAboveOverlappingPreferenceContent() {
        SwingUtilities.invokeAndWait {
            variants.forEach { variant ->
                scene(variant, badge = "123456789012345", overlappingTitle = true).use { scene ->
                    scene.render(0).close()
                    scene.render(1_000_000_000L).use { image ->
                        val nodes = nodes(scene)
                        val badge = nodes.single { text(it) == "123456789012345" }.boundsInRoot
                        val title = nodes.single {
                            it.config.getOrNull(SemanticsProperties.TestTag) == "title"
                        }.boundsInRoot
                        val overlap = badge.intersect(title)
                        assertTrue(overlap.width > 4 && overlap.height > 4, "${variant.name}: forced overlap")
                        val pixels = image.toComposeImageBitmap().toPixelMap()
                        for (y in ceil(overlap.top + 2).toInt() until floor(overlap.bottom - 2).toInt()) {
                            for (x in ceil(overlap.left + 2).toInt() until floor(overlap.right - 2).toInt()) {
                                assertTrue(pixels[x, y] != Color.Magenta, "${variant.name}: badge above title at $x,$y")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun compactBadgePreservesPreferenceLayoutAtLargeTextSizesAndInRtl() {
        SwingUtilities.invokeAndWait {
            variants.forEach { variant ->
                val originalTitle = scene(variant, badge = null).use { scene ->
                    scene.render(0).close()
                    nodes(scene).single { text(it) == "Request log" }.boundsInRoot
                }
                scene(variant, badge = "1.5K").use { scene ->
                    scene.render(0).close()
                    scene.render(1_000_000_000L).use { image ->
                        val folder = File("build/reports/setting-badge").apply { mkdirs() }
                        image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                            File(folder, "${variant.name}.png").writeBytes(data.bytes)
                        }
                    }
                    val nodes = nodes(scene)
                    assertEquals(originalTitle, nodes.single { text(it) == "Request log" }.boundsInRoot)
                    val badge = nodes.single { text(it) == "1.5K" }.boundsInRoot
                    assertTrue(badge.left >= 0 && badge.right <= 390, "${variant.name}: horizontal fit")
                    assertTrue(badge.top >= 0 && badge.bottom <= 160, "${variant.name}: vertical fit")
                }
            }
        }
    }

    private fun scene(
        variant: Variant,
        badge: String?,
        overlappingTitle: Boolean = false,
    ) = ImageComposeScene(
        width = 390,
        height = 160,
        density = Density(1f, variant.fontScale),
        layoutDirection = variant.direction,
    ) {
        WizardPreview(dark = variant.dark) {
            SettingPaneComponentsDefault.KgAction(
                icon = Icons.Outlined.History,
                badge = badge,
                title = {
                    if (overlappingTitle) {
                        Box(Modifier.fillMaxWidth().height(64.dp).background(Color.Magenta).testTag("title"))
                    } else {
                        Text("Request log")
                    }
                },
                trailing = { ChevronIcon() },
                onClick = {},
            )
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private fun text(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    private val variants = listOf(
        Variant("default"),
        Variant("dark", dark = true),
        Variant("large", fontScale = 1.6f),
        Variant("rtl", direction = LayoutDirection.Rtl),
        Variant("large-rtl", fontScale = 1.6f, direction = LayoutDirection.Rtl),
    )

    private data class Variant(
        val name: String,
        val fontScale: Float = 1f,
        val dark: Boolean = false,
        val direction: LayoutDirection = LayoutDirection.Ltr,
    )
}
