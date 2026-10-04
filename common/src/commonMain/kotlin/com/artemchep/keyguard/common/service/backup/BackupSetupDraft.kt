package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password

/** A complete, memory-only configuration; credentials and folder grants never cross the UI bridge. */
class BackupSetupDraft(initial: BackupConfig) {
    var config: BackupConfig = initial
        private set

    private var savedConfig = initial
    private var stores = BackupStoreDrafts()

    fun reset(saved: BackupConfig) {
        savedConfig = saved
        config = saved
        stores = BackupStoreDrafts()
    }

    fun setStoreKind(kind: BackupStoreKind) {
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

    fun setS3(
        endpoint: String,
        region: String,
        bucket: String,
        prefix: String,
        accessKeyId: String,
        secretAccessKey: String,
        pathStyle: Boolean,
    ) {
        val store = BackupStoreConfig.S3(
            endpoint = endpoint,
            region = region,
            bucket = bucket,
            prefix = prefix,
            accessKeyId = accessKeyId,
            pathStyle = pathStyle,
        ).sanitized()
        setStore(
            store.copy(
                secretAccessKey = secretAccessKey.takeIf { it.isNotEmpty() }?.let(::Password)
                    ?: keptS3SecretAccessKey(store),
            ),
        )
    }

    /** Whether [setS3] with an empty secret access key keeps the key of this account. */
    fun keepsS3SecretAccessKey(
        endpoint: String,
        bucket: String,
        accessKeyId: String,
    ): Boolean {
        val store = BackupStoreConfig.S3(
            endpoint = endpoint,
            bucket = bucket,
            accessKeyId = accessKeyId,
        ).sanitized()
        return keptS3SecretAccessKey(store) != null
    }

    private fun keptS3SecretAccessKey(store: BackupStoreConfig.S3): Password? {
        val previous = config.store as? BackupStoreConfig.S3
        val saved = savedConfig.store as? BackupStoreConfig.S3
        return previous?.secretAccessKey?.takeIf { previous.isSameS3Account(store) }
            ?: saved?.secretAccessKey?.takeIf { saved.isSameS3Account(store) }
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
            retention = BackupRetention(
                maxSnapshots.coerceIn(BackupRetention.NEVER_CLEAR_MAX_SNAPSHOTS, BackupRetention.MAX_SNAPSHOTS_LIMIT),
            ),
        )
    }
}

/** Whether both stores have the account that a secret access key belongs to. */
private fun BackupStoreConfig.S3.isSameS3Account(
    other: BackupStoreConfig.S3,
): Boolean = endpoint == other.endpoint && bucket == other.bucket && accessKeyId == other.accessKeyId
