package com.artemchep.keyguard.feature.home.settings.backups

import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupStatus

data class AutomaticBackupsSettingsState(
    val config: BackupConfig,
    val status: BackupStatus,
    val onSetupClick: () -> Unit,
    val onRetentionChange: (Int) -> Unit,
    val onRunNow: () -> Unit,
    /** Imports a snapshot of the configured backup into an account. */
    val onImport: () -> Unit,
    val onDisableClick: () -> Unit,
    /** Whether to offer the encryption upgrade; [onUpgradeEncryption] is null while a run is active. */
    val canUpgradeEncryption: Boolean = false,
    val onUpgradeEncryption: (() -> Unit)? = null,
)
