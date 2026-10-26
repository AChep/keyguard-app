package com.artemchep.keyguard.feature.export

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.artemchep.keyguard.ui.wizard.renderScene
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders the export wizard and checks that the primary action stays in the viewport. */
class ExportWizardScreenTest {
    @Test
    fun `named previews render without app services`() {
        SwingUtilities.invokeAndWait {
            previews().forEach { (name, content) ->
                render(Variant(name, 390, 844), name, content) { nodes ->
                    assertTrue(
                        nodes.any { !it.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() },
                        "$name: preview renders accessible text without app services",
                    )
                }
            }
        }
    }

    @Test
    fun `steps fit narrow wide dark standard and large text rtl layouts`() {
        val variants = listOf(
            Variant("phone", 390, 844),
            Variant("desktop-dark", 1024, 800, dark = true),
            Variant("compact-standard", 360, 640, expressive = false),
            Variant("rtl-large-text", 390, 844, fontScale = 1.6f, rtl = true),
        )
        SwingUtilities.invokeAndWait {
            variants.forEach { variant ->
                ExportStep.entries.forEach { step ->
                    val data = when (step) {
                        ExportStep.Review -> ExportPreviewData.review()
                        else -> ExportPreviewData(step = step)
                    }
                    renderWizard(variant, data, "${variant.name}-${step.name}") { nodes ->
                        if (step == ExportStep.Format) assertFormatChoices(nodes)
                        val action = primaryAction(nodes, step)
                        val bounds = action.boundsInRoot
                        val name = "${variant.name}-${step.name}"
                        assertTrue(bounds.width > 0 && bounds.height > 0, "$name: action has visible bounds")
                        assertTrue(bounds.left >= 0 && bounds.right <= variant.width, "$name: action fits horizontally")
                        assertTrue(bounds.top >= 0 && bounds.bottom <= variant.height, "$name: action fits vertically")
                    }
                }
            }
        }
    }

    @Test
    fun `a password mismatch shows an error`() {
        val data = ExportPreviewData(
            step = ExportStep.Password,
            password = "secret",
            confirmation = "different",
        )
        SwingUtilities.invokeAndWait {
            renderWizard(Variant("password", 390, 844), data, "password-mismatch") { nodes ->
                assertTrue(nodes.any { it.hasText("The passwords do not match.") })
                assertTrue(isDisabled(primaryAction(nodes, ExportStep.Password)))
            }
        }
    }

    @Test
    fun `review without the write permission asks for it and can not export`() {
        val data = ExportPreviewData.review().copy(permissionDeclined = true)
        SwingUtilities.invokeAndWait {
            renderWizard(Variant("permission", 390, 844), data, "review-permission") { nodes ->
                assertTrue(nodes.any { it.hasText("Grant write external storage permission") })
                assertTrue(isDisabled(primaryAction(nodes, ExportStep.Review)))
            }
        }
    }

    private fun renderWizard(
        variant: Variant,
        data: ExportPreviewData,
        name: String,
        check: (List<SemanticsNode>) -> Unit,
    ) = render(
        variant = variant,
        name = name,
        content = { ExportWizardPreview(data, dark = variant.dark, expressive = variant.expressive) },
        check = check,
    )

    private fun render(
        variant: Variant,
        name: String,
        content: @Composable () -> Unit,
        check: (List<SemanticsNode>) -> Unit,
    ) = renderScene(
        folder = "build/reports/export-wizard",
        name = name,
        width = variant.width,
        height = variant.height,
        fontScale = variant.fontScale,
        rtl = variant.rtl,
        content = content,
        check = check,
    )

    private fun primaryAction(nodes: List<SemanticsNode>, step: ExportStep): SemanticsNode {
        val text = if (step == ExportStep.Review) "Export" else "Continue"
        val action = nodes.firstOrNull { it.hasText(text) }
        assertTrue(action != null, "$step: primary action is accessible")
        return action
    }

    private fun assertFormatChoices(nodes: List<SemanticsNode>) {
        val choices = nodes.filter { it.config.getOrNull(SemanticsProperties.Selected) != null }
        assertEquals(2, choices.size)
        choices.forEach { choice ->
            assertTrue(choice.config.getOrNull(SemanticsActions.OnClick)?.action != null)
            assertTrue(!choice.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
        }
    }

    private fun isDisabled(node: SemanticsNode): Boolean = generateSequence(node) { it.parent }
        .any { it.config.getOrNull(SemanticsProperties.Disabled) != null }

    private fun SemanticsNode.hasText(text: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true

    private fun previews(): List<Pair<String, @Composable () -> Unit>> = listOf(
        "format" to { ExportFormatPreview() },
        "format-kdbx" to { ExportFormatKdbxPreview() },
        "items" to { ExportItemsPreview() },
        "items-attachments" to { ExportItemsAttachmentsPreview() },
        "password-mismatch" to { ExportPasswordMismatchPreview() },
        "review" to { ExportReviewPreview() },
        "review-permission" to { ExportReviewPermissionPreview() },
        "review-dark" to { ExportReviewDarkPreview() },
        "loading" to { ExportLoadingPreview() },
    )

    private data class Variant(
        val name: String,
        val width: Int,
        val height: Int,
        val dark: Boolean = false,
        val expressive: Boolean = true,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
    )
}
