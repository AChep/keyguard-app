package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.service.backup.BackupStoreConfig

/** Keeps both destination drafts for the lifetime of the settings editor. */
internal class BackupStoreDrafts {
    private var local = BackupStoreConfig.Local()
    private var webDav = BackupStoreConfig.WebDav()

    fun select(current: BackupStoreConfig, kind: String): BackupStoreConfig {
        // Remember the complete value, including local access tokens and WebDAV
        // credentials. An editor/picker updates only the active setup value.
        when (current) {
            is BackupStoreConfig.Local -> local = current
            is BackupStoreConfig.WebDav -> webDav = current
        }
        return when (kind) {
            "local" -> local
            "webdav" -> webDav
            else -> current
        }
    }
}
