package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupRetention
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig

/** A complete, memory-only configuration; credentials and folder grants never cross the UI bridge. */
internal class BackupSetupDraft(initial: BackupConfig) {
    var config: BackupConfig = initial
        private set

    private var savedConfig = initial
    private var stores = BackupStoreDrafts()

    fun reset(saved: BackupConfig) {
        savedConfig = saved
        config = saved
        stores = BackupStoreDrafts()
    }

    fun setStoreKind(kind: String) {
        config = config.copy(store = stores.select(config.store, kind))
    }

    fun setStore(store: BackupStoreConfig) {
        config = config.copy(store = store)
    }

    fun setWebDav(url: String, username: String, password: String) {
        val previous = config.store as? BackupStoreConfig.WebDav
        val newUrl = url.trim().takeIf { it.isNotEmpty() }
        val newUsername = username.trim().takeIf { it.isNotEmpty() }
        val unchangedAccount = previous?.url == newUrl && previous?.username == newUsername
        val saved = savedConfig.store as? BackupStoreConfig.WebDav
        val matchesSavedAccount = saved?.url == newUrl && saved?.username == newUsername
        setStore(
            BackupStoreConfig.WebDav(
                url = newUrl,
                username = newUsername,
                password = password.takeIf { it.isNotEmpty() }?.let(::Password)
                    ?: previous?.password?.takeIf { unchangedAccount }
                    ?: saved?.password?.takeIf { matchesSavedAccount },
            ),
        )
    }

    fun setPassword(text: String) {
        config = config.copy(password = text.takeIf { it.isNotEmpty() }?.let(::Password))
    }

    fun restorePassword(saved: BackupConfig) {
        config = config.copy(password = saved.password)
    }

    fun setIncludeAttachments(value: Boolean) {
        config = config.copy(includeAttachments = value)
    }

    fun setRetention(maxSnapshots: Int) {
        config = config.copy(
            retention = BackupRetention(maxSnapshots.coerceIn(0, BackupRetention.MAX_SNAPSHOTS_LIMIT)),
        )
    }
}
