package com.artemchep.keyguard.feature.home.settings.backups

import com.artemchep.keyguard.common.service.backup.BackupStoreKind
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import kotlinx.coroutines.flow.Flow

internal data class AutomaticBackupsSetupState(
    val data: BackupSetupData,
    val password: TextFieldModel,
    val confirmationPassword: TextFieldModel,
    val filePickerIntentFlow: Flow<FilePickerIntent<*>>,
    val onStoreKindChange: (BackupStoreKind) -> Unit,
    val onLocationClick: (() -> Unit)?,
    val onIncludeAttachmentsChange: ((Boolean) -> Unit)?,
    val onRetentionChange: ((Int) -> Unit)?,
    val onBack: () -> Unit,
    val onContinue: (() -> Unit)?,
    val onDiscard: () -> Unit,
    val onDismissDiscard: () -> Unit,
)
