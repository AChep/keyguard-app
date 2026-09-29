package com.artemchep.keyguard.common.service.backup

/** Keeps both destination drafts for the lifetime of the settings editor. */
internal class BackupStoreDrafts {
    private var local = BackupStoreConfig.Local()
    private var webDav = BackupStoreConfig.WebDav()

    fun select(current: BackupStoreConfig, kind: BackupStoreKind): BackupStoreConfig {
        // Remember the complete value, including local access tokens and WebDAV
        // credentials. An editor/picker updates only the active setup value.
        when (current) {
            is BackupStoreConfig.Local -> local = current
            is BackupStoreConfig.WebDav -> webDav = current
        }
        return when (kind) {
            BackupStoreKind.Local -> local
            BackupStoreKind.WebDav -> webDav
        }
    }
}
