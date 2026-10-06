package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.service.backup.BackupRetention
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.feature.s3.locationUriOrNull
import com.artemchep.keyguard.feature.s3.s3EndpointHostOrNull
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

    is BackupStoreConfig.S3 -> store.locationUriOrNull()
        ?.let { uri ->
            val host = s3EndpointHostOrNull(store.endpoint)
                ?: return@let uri
            stringResource(Res.string.s3_location_summary, uri, host)
        }
}

internal val BackupStoreConfig.icon: ImageVector
    get() = when (this) {
        is BackupStoreConfig.Local -> Icons.Outlined.Folder
        is BackupStoreConfig.WebDav -> Icons.Outlined.Cloud
        is BackupStoreConfig.S3 -> Icons.Outlined.Inventory2
    }
