package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.platform.iosKeyguardAtomicDataDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent

object PendingUploadDirProviderIos : PendingUploadDirProvider {
    override suspend fun get(
        accountId: String,
        namespace: String,
    ): PendingUploadDirectory = iosKeyguardAtomicDataDirectory()
        .resolveDirectory(AtomicPathComponent.parse("pending_uploads"))
        .resolveDirectory(AtomicPathComponent.parse(namespace))
        .resolveDirectory(AtomicPathComponent.parse(accountId))
}
