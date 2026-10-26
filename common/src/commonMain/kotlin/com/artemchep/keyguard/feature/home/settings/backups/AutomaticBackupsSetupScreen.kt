package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.wizard.WizardBackButton
import com.artemchep.keyguard.ui.wizard.WizardBottomBar
import com.artemchep.keyguard.ui.wizard.WizardHeader
import com.artemchep.keyguard.ui.wizard.WizardScaffold
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AutomaticBackupsSetupScreen() {
    val state = produceAutomaticBackupsSetupState()
    state.fold(
        ifLoading = { AutomaticBackupsSetupContent(null) },
        ifOk = {
            FilePickerEffect(it.filePickerIntentFlow)
            AutomaticBackupsSetupContent(it)
        },
    )
}

@Composable
internal fun AutomaticBackupsSetupContent(state: AutomaticBackupsSetupState?) {
    WizardScaffold(
        title = stringResource(Res.string.pref_item_automatic_backups_wizard_title),
        step = state?.data?.step,
        navigationIcon = {
            WizardBackButton(
                onBack = state?.onBack,
                enabled = state?.data?.isSaving != true,
            )
        },
        bottomBar = {
            if (state != null) BackupSetupBottomBar(state)
        },
    ) { step ->
        if (state != null) {
            BackupSetupHeader(step)
            when (step) {
                BackupSetupStep.Destination -> BackupSetupDestination(state)
                BackupSetupStep.Protection -> BackupSetupProtection(state)
                BackupSetupStep.Contents -> BackupSetupContents(state)
                BackupSetupStep.Review -> BackupSetupReview(state)
            }
        }
    }
    if (state?.data?.confirmingDiscard == true) {
        BackupSetupDiscardDialog(state)
    }
}

@Composable
private fun BackupSetupDiscardDialog(state: AutomaticBackupsSetupState) {
    AlertDialog(
        onDismissRequest = state.onDismissDiscard,
        title = { Text(stringResource(Res.string.pref_item_automatic_backups_wizard_discard_title)) },
        text = { Text(stringResource(Res.string.pref_item_automatic_backups_wizard_discard_message)) },
        confirmButton = {
            TextButton(onClick = state.onDiscard) {
                Text(stringResource(Res.string.pref_item_automatic_backups_wizard_discard_action))
            }
        },
        dismissButton = {
            TextButton(onClick = state.onDismissDiscard) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
private fun BackupSetupBottomBar(state: AutomaticBackupsSetupState) {
    val text = stringResource(
        when {
            state.data.isSaving -> Res.string.pref_item_automatic_backups_wizard_verifying_status
            state.data.step == BackupSetupStep.Review ->
                Res.string.pref_item_automatic_backups_enable_button
            else -> Res.string.continue_
        },
    )
    WizardBottomBar(
        text = text,
        onClick = state.onContinue,
        loading = state.data.isSaving,
    )
}

@Composable
private fun BackupSetupHeader(step: BackupSetupStep) {
    val title = when (step) {
        BackupSetupStep.Destination -> Res.string.pref_item_automatic_backups_wizard_destination_title
        BackupSetupStep.Protection -> Res.string.pref_item_automatic_backups_wizard_protection_title
        BackupSetupStep.Contents -> Res.string.pref_item_automatic_backups_wizard_contents_title
        BackupSetupStep.Review -> Res.string.pref_item_automatic_backups_wizard_review_title
    }
    val detail = when (step) {
        BackupSetupStep.Destination -> Res.string.pref_item_automatic_backups_wizard_destination_detail
        BackupSetupStep.Protection -> Res.string.pref_item_automatic_backups_wizard_protection_detail
        BackupSetupStep.Contents -> Res.string.pref_item_automatic_backups_wizard_contents_detail
        BackupSetupStep.Review -> Res.string.pref_item_automatic_backups_wizard_review_detail
    }
    WizardHeader(
        stepNumber = step.ordinal + 1,
        stepCount = BackupSetupStep.entries.size,
        title = stringResource(title),
        detail = stringResource(detail),
    )
}

@Composable
private fun AutomaticBackupsSetupPreview(
    data: BackupSetupData? = AutomaticBackupsPreviewData.setup(),
    dark: Boolean = false,
    expressive: Boolean = true,
) {
    AutomaticBackupsPreview(dark = dark, expressive = expressive) {
        AutomaticBackupsSetupContent(data?.let { automaticBackupsSetupPreviewState(it) })
    }
}

@Preview(name = "Destination — empty", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupDestinationEmptyPreview() {
    AutomaticBackupsSetupPreview(AutomaticBackupsPreviewData.setup(store = BackupStoreConfig.Local()))
}

@Preview(name = "Destination — local folder", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupDestinationLocalPreview() {
    AutomaticBackupsSetupPreview()
}

@Preview(name = "Destination — WebDAV", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupDestinationWebDavPreview() {
    AutomaticBackupsSetupPreview(AutomaticBackupsPreviewData.setup(store = AutomaticBackupsPreviewData.webDavStore))
}

@Preview(name = "Destination — invalid URL", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupDestinationInvalidPreview() {
    AutomaticBackupsSetupPreview(
        AutomaticBackupsPreviewData.setup(store = BackupStoreConfig.WebDav("https://alex@backup.example.com")),
    )
}

@Preview(name = "Protection — optional password", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupProtectionOptionalPreview() {
    AutomaticBackupsSetupPreview(AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Protection))
}

@Preview(name = "Protection — confirmed password", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupProtectionPasswordPreview() {
    AutomaticBackupsSetupPreview(
        AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Protection, password = "example-password"),
    )
}

@Preview(name = "Protection — mismatch", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupProtectionMismatchPreview() {
    AutomaticBackupsSetupPreview(
        AutomaticBackupsPreviewData.setup(
            step = BackupSetupStep.Protection,
            password = "example-password",
            confirmationPassword = "different-password",
        ),
    )
}

@Preview(name = "Contents", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupContentsPreview() {
    AutomaticBackupsSetupPreview(AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Contents))
}

@Preview(name = "Review", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupReviewPreview() {
    AutomaticBackupsSetupPreview(AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Review))
}

@Preview(name = "Review — verifying", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupVerifyingPreview() {
    AutomaticBackupsSetupPreview(AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Review, saving = true))
}

@Preview(name = "Review — verification failed", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupVerificationFailedPreview() {
    AutomaticBackupsSetupPreview(
        AutomaticBackupsPreviewData.setup(
            step = BackupSetupStep.Review,
            error = AutomaticBackupsPreviewData.failure.lastErrorMessage,
        ),
    )
}

@Preview(name = "Discard confirmation", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupDiscardPreview() {
    AutomaticBackupsSetupPreview(AutomaticBackupsPreviewData.setup(confirmingDiscard = true))
}

@Preview(name = "Loading", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupLoadingPreview() {
    AutomaticBackupsSetupPreview(data = null)
}

@Preview(name = "Review — dark", group = "Backup setup", widthDp = 390, heightDp = 844)
@Composable
internal fun BackupSetupReviewDarkPreview() {
    AutomaticBackupsSetupPreview(AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Review), dark = true)
}

@Preview(name = "Review — wide", group = "Backup setup", widthDp = 1024, heightDp = 800)
@Composable
internal fun BackupSetupReviewWidePreview() {
    AutomaticBackupsSetupPreview(AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Review))
}

@Preview(name = "Destination — compact standard", group = "Backup setup", widthDp = 360, heightDp = 640)
@Composable
internal fun BackupSetupCompactStandardPreview() {
    AutomaticBackupsSetupPreview(expressive = false)
}

@Preview(
    name = "Protection — large text RTL",
    group = "Backup setup",
    widthDp = 390,
    heightDp = 844,
    fontScale = 1.6f,
    locale = "ar",
)
@Composable
internal fun BackupSetupLargeTextRtlPreview() {
    AutomaticBackupsSetupPreview(AutomaticBackupsPreviewData.setup(step = BackupSetupStep.Protection))
}
