package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.platform.appleKeyguardAtomicDataDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent

object PendingUploadDirProviderApple : PendingUploadDirProvider {
    override suspend fun get(
        accountId: String,
        namespace: String,
    ): PendingUploadDirectory = appleKeyguardAtomicDataDirectory()
        .resolveDirectory(AtomicPathComponent.parse("pending_uploads"))
        .resolveDirectory(AtomicPathComponent.parse(namespace))
        .resolveDirectory(AtomicPathComponent.parse(accountId))
}
