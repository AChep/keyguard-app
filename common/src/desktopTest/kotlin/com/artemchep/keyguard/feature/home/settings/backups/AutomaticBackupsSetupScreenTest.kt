package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders real screen components and checks that the primary action stays in the viewport. */
@OptIn(ExperimentalComposeUiApi::class)
class AutomaticBackupsSetupScreenTest {
    @Test
    fun `named previews render without app services`() {
        SwingUtilities.invokeAndWait {
            (wizardPreviews() + settingsPreviews()).forEach { preview ->
                renderScene(
                    folder = "build/reports/backup-previews",
                    name = preview.name,
                    width = preview.width,
                    height = preview.height,
                    fontScale = preview.fontScale,
                    rtl = preview.rtl,
                    content = preview.content,
                ) { nodes ->
                    assertTrue(
                        nodes.any { !it.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() },
                        "${preview.name}: preview renders accessible text without app services",
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
                BackupSetupStep.entries.forEach { step ->
                    render(variant, AutomaticBackupsPreviewData.setup(step), "${variant.name}-${step.name}")
                }
            }
            render(
                Variant("error", 390, 844),
                AutomaticBackupsPreviewData.setup(BackupSetupStep.Review, error = "Destination unavailable"),
                "review-error",
            )
            render(
                Variant("busy", 390, 844),
                AutomaticBackupsPreviewData.setup(BackupSetupStep.Review, saving = true),
                "review-verifying",
            )
            render(
                Variant("password", 390, 844),
                AutomaticBackupsPreviewData.setup(
                    BackupSetupStep.Protection,
                    password = "secret",
                    confirmationPassword = "different",
                ),
                "password-mismatch",
            )
            render(
                Variant("destination", 390, 844),
                AutomaticBackupsPreviewData.setup(
                    BackupSetupStep.Destination,
                    store = BackupStoreConfig.WebDav("https://user@example.com"),
                ),
                "destination-error",
            )
        }
    }

    private fun render(variant: Variant, data: BackupSetupData, name: String) {
        renderScene(
            folder = "build/reports/backup-setup",
            name = name,
            width = variant.width,
            height = variant.height,
            fontScale = variant.fontScale,
            rtl = variant.rtl,
            content = {
                AutomaticBackupsPreview(dark = variant.dark, expressive = variant.expressive) {
                    AutomaticBackupsSetupContent(automaticBackupsSetupPreviewState(data))
                }
            },
        ) { nodes ->
            if (data.step == BackupSetupStep.Destination) assertDestinationChoices(nodes)
            val expected = when {
                data.isSaving -> "Verifying destination"
                data.step == BackupSetupStep.Review -> "Enable backups"
                else -> "Continue"
            }
            val action = nodes.firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(expected) } == true
            }
            assertTrue(action != null, "$name: primary action is accessible")
            val bounds = action.boundsInRoot
            assertTrue(bounds.width > 0 && bounds.height > 0, "$name: action has visible bounds")
            assertTrue(bounds.left >= 0 && bounds.right <= variant.width, "$name: action fits horizontally")
            assertTrue(bounds.top >= 0 && bounds.bottom <= variant.height, "$name: action fits vertically")
        }
    }

    /** Renders a settled frame to [folder] and runs [check] while the semantics tree is attached. */
    private fun renderScene(
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

    private fun assertDestinationChoices(nodes: List<SemanticsNode>) {
        val choices = nodes.filter { it.config.getOrNull(SemanticsProperties.Selected) != null }
        assertEquals(2, choices.size)
        choices.forEach { choice ->
            assertTrue(choice.config.getOrNull(SemanticsActions.OnClick)?.action != null)
            assertTrue(!choice.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
        }
    }

    private fun wizardPreviews() = listOf(
        PreviewCase("destination-empty") { BackupSetupDestinationEmptyPreview() },
        PreviewCase("destination-local") { BackupSetupDestinationLocalPreview() },
        PreviewCase("destination-webdav") { BackupSetupDestinationWebDavPreview() },
        PreviewCase("destination-invalid") { BackupSetupDestinationInvalidPreview() },
        PreviewCase("protection-optional") { BackupSetupProtectionOptionalPreview() },
        PreviewCase("protection-password") { BackupSetupProtectionPasswordPreview() },
        PreviewCase("protection-mismatch") { BackupSetupProtectionMismatchPreview() },
        PreviewCase("contents") { BackupSetupContentsPreview() },
        PreviewCase("review") { BackupSetupReviewPreview() },
        PreviewCase("verifying") { BackupSetupVerifyingPreview() },
        PreviewCase("verification-failed") { BackupSetupVerificationFailedPreview() },
        PreviewCase("discard") { BackupSetupDiscardPreview() },
        PreviewCase("loading") { BackupSetupLoadingPreview() },
        PreviewCase("review-dark") { BackupSetupReviewDarkPreview() },
        PreviewCase("review-wide", width = 1024, height = 800) { BackupSetupReviewWidePreview() },
        PreviewCase("compact-standard", width = 360, height = 640) { BackupSetupCompactStandardPreview() },
        PreviewCase("large-text-rtl", fontScale = 1.6f, rtl = true) { BackupSetupLargeTextRtlPreview() },
    )

    private fun settingsPreviews() = listOf(
        PreviewCase("settings-disabled") { AutomaticBackupsSettingsDisabledPreview() },
        PreviewCase("settings-enabled") { AutomaticBackupsSettingsEnabledPreview() },
        PreviewCase("settings-dark") { AutomaticBackupsSettingsDarkPreview() },
        PreviewCase("setup-intro", height = 400) { AutomaticBackupsSetupIntroPreview() },
        PreviewCase("retention-preset", height = 100) { AutomaticBackupsRetentionPresetPreview() },
        PreviewCase("retention-custom", height = 100) { AutomaticBackupsRetentionCustomPreview() },
        PreviewCase("retention-never-clear", height = 100) { AutomaticBackupsRetentionNeverClearPreview() },
        PreviewCase("disable-action", height = 80) { AutomaticBackupsDisableActionPreview() },
        PreviewCase("status-empty", height = 320) { AutomaticBackupsStatusEmptyPreview() },
        PreviewCase("status-success", height = 320) { AutomaticBackupsStatusSuccessPreview() },
        PreviewCase("status-failure", height = 320) { AutomaticBackupsStatusFailurePreview() },
        PreviewCase("status-skipped", height = 320) { AutomaticBackupsStatusSkippedPreview() },
        PreviewCase("status-running", height = 320) { AutomaticBackupsStatusRunningPreview() },
        PreviewCase("status-preparing", height = 320) { AutomaticBackupsStatusPreparingPreview() },
        PreviewCase("status-expanded", height = 800) { AutomaticBackupsStatusExpandedPreview() },
        PreviewCase("details", height = 1800) { AutomaticBackupsDetailsPreview() },
    )

    private data class PreviewCase(
        val name: String,
        val width: Int = 390,
        val height: Int = 844,
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val content: @Composable () -> Unit,
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
