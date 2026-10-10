package com.artemchep.keyguard.feature.barcodetype

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.artemchep.keyguard.copy.GetBarcodeImageJvm
import com.artemchep.keyguard.feature.dialog.DialogContent
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.koin.compose.KoinContext
import org.koin.core.Koin
import org.koin.dsl.module

@OptIn(ExperimentalComposeUiApi::class)
class BarcodeTypeScreenTest {
    @Test
    fun `complete barcode decodes after scrolling at different viewport sizes`() {
        val variants = listOf(
            Variant("desktop", 1600, 1200, density = 2f),
            Variant("no-description", 1600, 1200, density = 2f, description = null),
            Variant("short-desktop", 1600, 800, density = 2f),
            Variant("phone", 390, 844),
            Variant("large-text", 1000, 900, density = 2f, fontScale = 1.6f),
            Variant("small-bitmap", 1000, 600, density = 2f),
        )
        SwingUtilities.invokeAndWait {
            variants.forEach { variant ->
                withScene(variant) {
                    val formats = if (variant.name == "small-bitmap") {
                        listOf(BarcodeImageFormat.QR_CODE)
                    } else {
                        listOf(BarcodeImageFormat.QR_CODE, BarcodeImageFormat.CODE_128)
                    }
                    formats.forEach { format ->
                        this.format.value = format
                        revealAndDecode("${variant.name}-${format.name}")
                    }
                }
            }
        }
    }

    @Test
    fun `barcode fits after resize and switching back to QR with existing scroll position`() {
        SwingUtilities.invokeAndWait {
            withScene(Variant("resize", 1600, 1200, density = 2f)) {
                revealAndDecode("before-resize")
                scene.constraints = Constraints(maxWidth = 1600, maxHeight = 800)
                revealAndDecode("after-resize")
                format.value = BarcodeImageFormat.CODE_128
                revealAndDecode("resized-code128")
                format.value = BarcodeImageFormat.QR_CODE
                revealAndDecode("resized-qr")
            }
        }
    }

    private fun withScene(variant: Variant, block: Harness.() -> Unit) {
        val barcodeModule = module {
            single<GetBarcodeImage> { GetBarcodeImageJvm(Dispatchers.Unconfined) }
        }
        val koin = Koin().apply {
            loadModules(listOf(barcodeModule))
        }
        try {
            val harness = Harness(variant)
            ImageComposeScene(
                width = variant.width,
                height = variant.height,
                density = Density(variant.density, variant.fontScale),
            ) {
                KoinContext(koin) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Surface(
                                modifier = Modifier
                                    .widthIn(max = 560.dp)
                                    .fillMaxWidth(0.9f)
                                    .padding(16.dp),
                            ) {
                                DialogContent(
                                    icon = { Icon(Icons.Outlined.QrCode, contentDescription = null) },
                                    title = { Text("Barcode") },
                                    content = {
                                        harness.viewportWidth = viewportMaxWidth
                                        harness.viewportHeight = viewportMaxHeight
                                        BarcodeTypeBody(
                                            args = BarcodeTypeRoute.Args(
                                                text = variant.description,
                                                data = Payload,
                                                format = harness.format.value,
                                                disallowFormatSelection = false,
                                            ),
                                            loadableState = harness.state(),
                                        )
                                    },
                                    actions = {
                                        TextButton(onClick = {}) { Text("Close") }
                                    },
                                )
                            }
                        }
                    }
                }
            }.use { scene ->
                harness.scene = scene
                harness.block()
            }
        } finally {
            koin.close()
        }
    }

    private class Harness(val variant: Variant) {
        lateinit var scene: ImageComposeScene
        val format = mutableStateOf(BarcodeImageFormat.QR_CODE)
        var viewportWidth = Dp.Unspecified
        var viewportHeight = Dp.Unspecified
        private var frameTime = 0L

        fun state() = Loadable.Ok(
            BarcodeTypeState(
                format = BarcodeTypeState.Format(
                    format = format.value.name,
                    options = emptyList(),
                ),
                request = BarcodeImageRequest(
                    format = format.value,
                    data = Payload,
                ),
            ),
        )

        private fun settle() {
            repeat(5) {
                scene.render(frameTime).close()
                frameTime += 200_000_000L
            }
        }

        fun revealAndDecode(name: String) {
            settle()
            val nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            val scroll = nodes.single { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            val viewport = scroll.boundsInRoot
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            // The API must expose the visible body bounds, not the unbounded scroll content.
            assertEquals(viewport.width, viewportWidth.value * variant.density, 1f, name)
            assertTrue(viewportHeight.value.isFinite(), name)
            if (range.maxValue() > 0f) {
                assertEquals(viewport.height, viewportHeight.value * variant.density, 1f, name)
            } else {
                assertTrue(viewport.height <= viewportHeight.value * variant.density + 1f, name)
            }
            val delta = if (variant.description != null) {
                val description = nodes.single {
                    it.config.getOrNull(SemanticsProperties.Text)
                        ?.any { text -> text.text == variant.description } == true
                }
                // The description follows the padded barcode with a 16dp spacer.
                description.positionInRoot.y - 16f * variant.density - viewport.bottom
            } else {
                range.maxValue() - range.value()
            }
            val scrollBy = scroll.config[SemanticsActions.ScrollBy].action!!
            assertTrue(scrollBy(0f, delta), name)
            settle()
            captureAndDecode(name, scroll)
        }

        private fun captureAndDecode(name: String, scroll: SemanticsNode) {
            val viewport = scroll.boundsInRoot
            scene.render(frameTime).use { image ->
                val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                val directory = File("build/reports/barcode-previews").apply { mkdirs() }
                File(directory, "$name.png").writeBytes(bytes)
                val rendered = ImageIO.read(ByteArrayInputStream(bytes))
                val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                val edgeX = (viewport.left + 2f * variant.density).toInt()
                val inset = (2f * variant.density).toInt()
                if (range.value() > 0f) {
                    val top = viewport.top.toInt()
                    assertNotEquals(rendered.getRGB(edgeX, top + inset), rendered.getRGB(edgeX, top), name)
                }
                if (range.value() < range.maxValue()) {
                    val bottom = viewport.bottom.toInt() - 1
                    assertNotEquals(rendered.getRGB(edgeX, bottom - inset), rendered.getRGB(edgeX, bottom), name)
                }
                val bitmap = BinaryBitmap(
                    HybridBinarizer(
                        BufferedImageLuminanceSource(rendered),
                    ),
                )
                val decoded = MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))
                assertEquals(Payload, decoded.text, name)
            }
        }
    }

    private data class Variant(
        val name: String,
        val width: Int,
        val height: Int,
        val density: Float = 1f,
        val fontScale: Float = 1f,
        val description: String? = Description,
    )

    private companion object {
        const val Payload = "password"
        val Description = "Additional barcode information.\n".repeat(30)

        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)
    }
}
