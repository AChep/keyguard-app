package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.scaffoldContentWindowInsets
import com.artemchep.keyguard.ui.screenMaxWidthCompact
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AutomaticBackupsSetupContent(state: AutomaticBackupsSetupState?) {
    val scrollBehavior = ToolbarBehavior.behavior()
    val step = state?.data?.step
    val listState = remember(step) { LazyListState() }
    ScaffoldLazyColumn(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        listState = listState,
        topBar = {
            LargeToolbar(
                title = { Text(stringResource(Res.string.pref_item_automatic_backups_wizard_title)) },
                navigationIcon = { BackupSetupBackButton(state) },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (state != null) BackupSetupBottomBar(state)
        },
    ) {
        item("setup") {
            BackupSetupColumn {
                if (state == null) {
                    KeyguardLoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    Crossfade(
                        targetState = state.data.step,
                        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        label = "backup_setup_step",
                    ) { currentStep ->
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.verticalPadding)) {
                            BackupSetupHeader(currentStep)
                            when (currentStep) {
                                BackupSetupStep.Destination -> BackupSetupDestination(state)
                                BackupSetupStep.Protection -> BackupSetupProtection(state)
                                BackupSetupStep.Contents -> BackupSetupContents(state)
                                BackupSetupStep.Review -> BackupSetupReview(state)
                            }
                        }
                    }
                }
            }
        }
    }
    if (state?.data?.confirmingDiscard == true) {
        BackupSetupDiscardDialog(state)
    }
}

@Composable
private fun BackupSetupBackButton(state: AutomaticBackupsSetupState?) {
    val focusManager = LocalFocusManager.current
    if (state == null) {
        NavigationIcon()
    } else {
        IconButton(
            enabled = !state.data.isSaving,
            onClick = {
                focusManager.clearFocus()
                state.onBack()
            },
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(Res.string.additem_key_generator_back_title),
            )
        }
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
private fun BackupSetupColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = screenMaxWidthCompact).fillMaxWidth()
                .padding(vertical = Dimens.verticalPadding),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackupSetupBottomBar(state: AutomaticBackupsSetupState) {
    val focusManager = LocalFocusManager.current
    Surface {
        Box(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(
                scaffoldContentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
        ) {
            BackupSetupColumn {
                Button(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.fieldHorizontalPadding),
                    shapes = ButtonDefaults.shapes(),
                    enabled = state.onContinue != null,
                    onClick = {
                        focusManager.clearFocus()
                        state.onContinue?.invoke()
                    },
                ) {
                    if (state.data.isSaving) {
                        KeyguardLoadingIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    Text(stringResource(when {
                        state.data.isSaving -> Res.string.pref_item_automatic_backups_wizard_verifying_status
                        state.data.step == BackupSetupStep.Review ->
                            Res.string.pref_item_automatic_backups_enable_button
                        else -> Res.string.continue_
                    }))
                }
            }
        }
    }
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
    Column(
        modifier = Modifier.padding(horizontal = Dimens.textHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.verticalPadding),
    ) {
        Text(
            text = stringResource(
                Res.string.pref_item_automatic_backups_wizard_step_label,
                step.ordinal + 1,
                BackupSetupStep.entries.size,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = { (step.ordinal + 1).toFloat() / BackupSetupStep.entries.size },
        )
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(detail),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
