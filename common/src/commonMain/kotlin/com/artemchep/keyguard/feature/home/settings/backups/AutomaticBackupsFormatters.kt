package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.backup.BackupRetention
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun retentionText(
    snapshots: Int,
): String = if (snapshots == BackupRetention.NEVER_CLEAR_MAX_SNAPSHOTS) {
    stringResource(Res.string.pref_item_automatic_backups_retention_never_clear_value)
} else {
    stringResource(
        Res.string.pref_item_automatic_backups_retention_value,
        snapshots,
    )
}

@Composable
internal fun backupLocationText(
    store: BackupStoreConfig,
): String? = when (store) {
    is BackupStoreConfig.Local -> store.path
        ?.takeIf { it.isNotBlank() }

    is BackupStoreConfig.WebDav -> store.url
        ?.takeIf { it.isNotBlank() }
}
