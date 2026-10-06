package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.service.backup.BackupStoreConfig

/** The store kind as it crosses the Swift bridge. */
internal fun BackupStoreConfig.bridgeKind(): String = when (this) {
    is BackupStoreConfig.Local -> "local"
    is BackupStoreConfig.WebDav -> "webdav"
    is BackupStoreConfig.S3 -> "s3"
}
