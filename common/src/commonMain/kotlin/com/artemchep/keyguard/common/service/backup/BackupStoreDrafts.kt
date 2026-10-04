package com.artemchep.keyguard.common.service.backup

/** Keeps every destination draft for the lifetime of the settings editor. */
internal class BackupStoreDrafts {
    private var local = BackupStoreConfig.Local()
    private var webDav = BackupStoreConfig.WebDav()
    private var s3 = BackupStoreConfig.S3()

    fun select(current: BackupStoreConfig, kind: BackupStoreKind): BackupStoreConfig {
        // Remember the complete value, including local access tokens and remote
        // credentials. An editor/picker updates only the active setup value.
        when (current) {
            is BackupStoreConfig.Local -> local = current
            is BackupStoreConfig.WebDav -> webDav = current
            is BackupStoreConfig.S3 -> s3 = current
        }
        return when (kind) {
            BackupStoreKind.Local -> local
            BackupStoreKind.WebDav -> webDav
            BackupStoreKind.S3 -> s3
        }
    }
}
