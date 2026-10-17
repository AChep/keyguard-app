package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.platform.AppleStorageDirectories
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent

class PendingUploadDirProviderApple(
    private val directories: AppleStorageDirectories,
) : PendingUploadDirProvider {
    override suspend fun get(
        accountId: String,
        namespace: String,
    ): PendingUploadDirectory = directories.data
        .resolveDirectory(AtomicPathComponent.parse("pending_uploads"))
        .resolveDirectory(AtomicPathComponent.parse(namespace))
        .resolveDirectory(AtomicPathComponent.parse(accountId))
}
