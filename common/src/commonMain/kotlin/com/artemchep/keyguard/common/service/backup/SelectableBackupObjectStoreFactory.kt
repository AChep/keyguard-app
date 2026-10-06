package com.artemchep.keyguard.common.service.backup

const val BackupLocalObjectStoreFactoryTag = "backup.objectStore.local"

class SelectableBackupObjectStoreFactory(
    private val localFactory: BackupObjectStoreFactory,
    private val webDavFactory: BackupObjectStoreFactory,
    private val s3Factory: BackupObjectStoreFactory,
) : BackupObjectStoreFactory {
    override suspend fun open(
        store: BackupStoreConfig,
    ): BackupObjectStore = when (store) {
        is BackupStoreConfig.Local -> localFactory.open(store)
        is BackupStoreConfig.WebDav -> webDavFactory.open(store)
        is BackupStoreConfig.S3 -> s3Factory.open(store)
    }
}
