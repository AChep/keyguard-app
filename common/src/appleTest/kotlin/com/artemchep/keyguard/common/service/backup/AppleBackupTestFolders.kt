package com.artemchep.keyguard.common.service.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

internal fun createAppleBackupFolder(): String {
    val path = NSTemporaryDirectory() + "keyguard-backup-test-" + NSUUID().UUIDString
    SystemFileSystem.createDirectories(Path(path))
    return path
}

@OptIn(ExperimentalForeignApi::class)
internal fun removeAppleBackupFolder(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

/** Runs [block] with a store over a new temporary folder, then removes the folder. */
internal suspend fun withAppleBackupFolder(
    factory: BackupObjectStoreFactory = AppleFolderBackupObjectStoreFactory(),
    block: suspend (root: String, store: BackupObjectStore) -> Unit,
) {
    val root = createAppleBackupFolder()
    try {
        factory.useStore(BackupStoreConfig.Local(root)) { store -> block(root, store) }
    } finally { removeAppleBackupFolder(root) }
}

internal suspend fun BackupObjectStoreFactory.useStore(
    config: BackupStoreConfig,
    block: suspend (BackupObjectStore) -> Unit,
) {
    val store = open(config)
    try { block(store) } finally { store.close() }
}
