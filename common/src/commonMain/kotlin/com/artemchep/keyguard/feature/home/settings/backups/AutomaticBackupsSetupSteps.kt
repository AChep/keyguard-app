package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.BackupStoreKind
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.verticalPaddingHalf
import com.artemchep.keyguard.ui.util.HorizontalDivider
import com.artemchep.keyguard.ui.wizard.WizardChoice
import com.artemchep.keyguard.ui.wizard.WizardSummaryRow
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BackupSetupDestination(state: AutomaticBackupsSetupState) {
    val store = state.data.config.store
    BackupDestinationChoices(
        store = store,
        onStoreKindChange = state.onStoreKindChange,
    )
    HorizontalDivider(
        modifier = Modifier
            .padding(vertical = Dimens.verticalPadding),
    )
    val updatedOnLocationClick by rememberUpdatedState(state.onLocationClick)
    FilledTonalButton(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        onClick = {
            updatedOnLocationClick?.invoke()
        },
    ) {
        val text = stringResource(
            when (store) {
                is BackupStoreConfig.Local -> Res.string.pref_item_automatic_backups_choose_folder_action
                is BackupStoreConfig.WebDav -> Res.string.pref_item_automatic_backups_webdav_server_title
                is BackupStoreConfig.S3 -> Res.string.pref_item_automatic_backups_s3_title
            },
        )
        Text(text)
    }
    val location = backupLocationText(store)
    if (location != null) {
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = location,
        )
    }
    if (store is BackupStoreConfig.WebDav && !store.url.isNullOrEmpty() && !state.data.destinationValid) {
        FlatSimpleNote(
            modifier = Modifier
                .padding(top = Dimens.verticalPadding),
            type = SimpleNote.Type.WARNING,
            text = stringResource(Res.string.pref_item_automatic_backups_wizard_invalid_url_error),
        )
    }
    if (store is BackupStoreConfig.S3 && !store.bucket.isNullOrEmpty() && !state.data.destinationValid) {
        FlatSimpleNote(
            modifier = Modifier
                .padding(top = Dimens.verticalPadding),
            type = SimpleNote.Type.WARNING,
            text = stringResource(Res.string.pref_item_automatic_backups_wizard_invalid_s3_error),
        )
    }
}

@Composable
private fun BackupDestinationChoices(
    store: BackupStoreConfig,
    onStoreKindChange: (BackupStoreKind) -> Unit,
) {
    Column(
        modifier = Modifier
            .selectableGroup(),
    ) {
        WizardChoice(
            title = stringResource(Res.string.pref_item_automatic_backups_local_folder_title),
            icon = Icons.Outlined.Folder,
            selected = store is BackupStoreConfig.Local,
            shapeState = ShapeState.START,
            onClick = { onStoreKindChange(BackupStoreKind.Local) },
        )
        WizardChoice(
            title = stringResource(Res.string.pref_item_automatic_backups_webdav_server_title),
            icon = Icons.Outlined.Cloud,
            selected = store is BackupStoreConfig.WebDav,
            shapeState = ShapeState.CENTER,
            onClick = { onStoreKindChange(BackupStoreKind.WebDav) },
        )
        WizardChoice(
            title = stringResource(Res.string.pref_item_automatic_backups_s3_title),
            icon = Icons.Outlined.Inventory2,
            selected = store is BackupStoreConfig.S3,
            shapeState = ShapeState.END,
            onClick = { onStoreKindChange(BackupStoreKind.S3) },
        )
    }
}

@Composable
internal fun BackupSetupProtection(state: AutomaticBackupsSetupState) {
    val focusManager = LocalFocusManager.current
    val hasPassword = state.password.text.isNotEmpty()
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.verticalPaddingHalf),
    ) {
        PasswordFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            value = state.password,
            label = stringResource(Res.string.pref_item_automatic_backups_password_optional_label),
            shapeState = if (hasPassword) ShapeState.START else ShapeState.ALL,
            clearButton = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
        )
        if (hasPassword) {
            PasswordFlatTextField(
                modifier = Modifier
                    .padding(horizontal = Dimens.fieldHorizontalPadding),
                value = state.confirmationPassword,
                label = stringResource(Res.string.pref_item_automatic_backups_wizard_confirm_password_label),
                shapeState = ShapeState.END,
                clearButton = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
        }
    }
    if (hasPassword) {
        Text(
            modifier = Modifier.padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.pref_item_automatic_backups_password_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun BackupSetupContents(state: AutomaticBackupsSetupState) {
    val components = LocalSettingPaneComponents.current
    Column {
        CompositionLocalProvider(LocalSettingItemShape provides ShapeState.START) {
            components.KgSwitch(
                icon = Icons.Outlined.Attachment,
                title = stringResource(Res.string.pref_item_automatic_backups_include_attachments_title),
                checked = state.data.config.includeAttachments,
                onCheckedChange = state.onIncludeAttachmentsChange,
            )
        }
        CompositionLocalProvider(LocalSettingItemShape provides ShapeState.END) {
            AutomaticBackupsRetentionRow(
                maxSnapshots = state.data.config.retention.maxSnapshots,
                onRetentionChange = state.onRetentionChange,
                components = components,
            )
        }
    }
}

@Composable
internal fun BackupSetupReview(state: AutomaticBackupsSetupState) {
    val config = state.data.config
    Column(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WizardSummaryRow(
            icon = config.store.icon,
            title = stringResource(Res.string.pref_item_automatic_backups_location_title),
            value = backupLocationText(config.store),
        )
        WizardSummaryRow(
            icon = Icons.Outlined.Password,
            title = stringResource(Res.string.pref_item_automatic_backups_password_title),
            value = stringResource(
                if (config.password != null) {
                    Res.string.pref_item_automatic_backups_password_set
                } else {
                    Res.string.pref_item_automatic_backups_password_not_set
                },
            ),
        )
        WizardSummaryRow(
            icon = Icons.Outlined.AttachFile,
            title = stringResource(Res.string.pref_item_automatic_backups_include_attachments_title),
            value = stringResource(
                if (config.includeAttachments) {
                    Res.string.pref_item_automatic_backups_include_attachments_enabled_summary
                } else {
                    Res.string.pref_item_automatic_backups_include_attachments_disabled_summary
                },
            ),
        )
        WizardSummaryRow(
            icon = Icons.Outlined.History,
            title = stringResource(Res.string.pref_item_automatic_backups_retention_title),
            value = retentionText(config.retention.maxSnapshots),
        )
    }
    state.data.error?.let { error ->
        FlatSimpleNote(
            type = SimpleNote.Type.ERROR,
            text = error,
        )
    }
}
