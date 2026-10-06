package com.artemchep.keyguard.feature.home.settings.backups

import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupStatus

data class AutomaticBackupsSettingsState(
    val config: BackupConfig,
    val status: BackupStatus,
    val onSetupClick: () -> Unit,
    val onRetentionChange: (Int) -> Unit,
    val onRunNow: () -> Unit,
    val onDisableClick: () -> Unit,
)
