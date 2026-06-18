package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.runtime.MutableState
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import kotlinx.coroutines.flow.Flow

data class AutomaticBackupsSettingsState(
    val config: BackupConfig,
    val status: BackupStatus,
    val setup: Setup,
    val filePickerIntentFlow: Flow<FilePickerIntent<*>>,
    val setupError: String?,
    val isTestingLocation: Boolean,
    val onLocationClick: () -> Unit,
    val onWebDavLocationClick: () -> Unit,
    val onPasswordClick: () -> Unit,
    val onEnableClick: () -> Unit,
    val onRetentionChange: (Int) -> Unit,
    val onRunNow: () -> Unit,
    val onDisableClick: () -> Unit,
) {
    data class Setup(
        val store: MutableState<BackupStoreConfig>,
        val password: MutableState<String>,
        val includeAttachments: MutableState<Boolean>,
    )
}
