package com.artemchep.keyguard.feature.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.service.export.model.ExportFormat
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.wizard.WizardBackButton
import com.artemchep.keyguard.ui.wizard.WizardBottomBar
import com.artemchep.keyguard.ui.wizard.WizardHeader
import com.artemchep.keyguard.ui.wizard.WizardPreview
import com.artemchep.keyguard.ui.wizard.WizardScaffold
import org.jetbrains.compose.resources.stringResource

@Composable
fun ExportScreen(
    args: ExportRoute.Args,
) {
    val loadableState = produceExportScreenState(
        args = args,
    )
    val title = args.title
        ?: stringResource(Res.string.exportaccount_header_title)
    ExportWizardContent(
        title = title,
        state = loadableState.getOrNull(),
    )
}

@Composable
internal fun ExportWizardContent(
    title: String,
    state: ExportState?,
) {
    val wizard = state?.wizardFlow?.collectAsState()?.value
    WizardScaffold(
        title = title,
        step = wizard?.step,
        navigationIcon = {
            WizardBackButton(
                onBack = wizard?.onBack,
            )
        },
        bottomBar = {
            if (state != null && wizard != null) {
                ExportWizardBottomBar(
                    state = state,
                    wizard = wizard,
                )
            }
        },
    ) { step ->
        if (state != null) {
            ExportWizardHeader(step)
            when (step) {
                ExportStep.Format -> ExportFormatStep(state)
                ExportStep.Items -> ExportItemsStep(state)
                ExportStep.Password -> ExportPasswordStep(state)
                ExportStep.Review -> ExportReviewStep(state)
            }
        }
    }
}

@Composable
private fun ExportWizardBottomBar(
    state: ExportState,
    wizard: ExportState.Wizard,
) {
    if (wizard.step == ExportStep.Review) {
        val content by state.contentFlow.collectAsState()
        WizardBottomBar(
            text = stringResource(Res.string.exportaccount_export_button),
            onClick = content.onExportClick,
        )
    } else {
        WizardBottomBar(
            text = stringResource(Res.string.continue_),
            onClick = wizard.onContinue,
        )
    }
}

@Composable
private fun ExportWizardHeader(step: ExportStep) {
    val title = when (step) {
        ExportStep.Format -> Res.string.exportaccount_wizard_format_title
        ExportStep.Items -> Res.string.exportaccount_wizard_items_title
        ExportStep.Password -> Res.string.exportaccount_wizard_password_title
        ExportStep.Review -> Res.string.exportaccount_wizard_review_title
    }
    val detail = when (step) {
        ExportStep.Format -> Res.string.exportaccount_wizard_format_detail
        ExportStep.Items -> Res.string.exportaccount_wizard_items_detail
        ExportStep.Password -> Res.string.exportaccount_wizard_password_detail
        ExportStep.Review -> Res.string.exportaccount_wizard_review_detail
    }
    WizardHeader(
        stepNumber = step.ordinal + 1,
        stepCount = ExportStep.entries.size,
        title = stringResource(title),
        detail = stringResource(detail),
    )
}

@Composable
internal fun ExportWizardPreview(
    data: ExportPreviewData? = ExportPreviewData(),
    dark: Boolean = false,
    expressive: Boolean = true,
) {
    WizardPreview(dark = dark, expressive = expressive) {
        ExportWizardContent(
            title = stringResource(Res.string.exportaccount_header_title),
            state = data?.let { exportPreviewState(it) },
        )
    }
}

@Preview(name = "Format", group = "Export", widthDp = 390, heightDp = 844)
@Composable
internal fun ExportFormatPreview() {
    ExportWizardPreview()
}

@Preview(name = "Format — KDBX", group = "Export", widthDp = 390, heightDp = 844)
@Composable
internal fun ExportFormatKdbxPreview() {
    ExportWizardPreview(ExportPreviewData(format = ExportFormat.Kdbx))
}

@Preview(name = "Items", group = "Export", widthDp = 390, heightDp = 844)
@Composable
internal fun ExportItemsPreview() {
    ExportWizardPreview(ExportPreviewData(step = ExportStep.Items))
}

@Preview(name = "Items — attachments", group = "Export", widthDp = 390, heightDp = 844)
@Composable
internal fun ExportItemsAttachmentsPreview() {
    ExportWizardPreview(ExportPreviewData(step = ExportStep.Items, attachmentsEnabled = true))
}

@Preview(name = "Password — mismatch", group = "Export", widthDp = 390, heightDp = 844)
@Composable
internal fun ExportPasswordMismatchPreview() {
    ExportWizardPreview(
        ExportPreviewData(
            step = ExportStep.Password,
            password = "example-password",
            confirmation = "different-password",
        ),
    )
}

@Preview(name = "Review", group = "Export", widthDp = 390, heightDp = 844)
@Composable
internal fun ExportReviewPreview() {
    ExportWizardPreview(ExportPreviewData.review())
}

@Preview(name = "Review — permission", group = "Export", widthDp = 390, heightDp = 844)
@Composable
internal fun ExportReviewPermissionPreview() {
    ExportWizardPreview(ExportPreviewData.review().copy(permissionDeclined = true))
}

@Preview(name = "Review — dark", group = "Export", widthDp = 390, heightDp = 844)
@Composable
internal fun ExportReviewDarkPreview() {
    ExportWizardPreview(ExportPreviewData.review(), dark = true)
}

@Preview(name = "Loading", group = "Export", widthDp = 390, heightDp = 844)
@Composable
internal fun ExportLoadingPreview() {
    ExportWizardPreview(data = null)
}
