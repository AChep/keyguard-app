package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.iosKeyguardDataDirectory
import com.artemchep.keyguard.platform.resolve

object PendingUploadDirProviderIos : PendingUploadDirProvider {
    override suspend fun get(
        accountId: String,
        namespace: String,
    ): LocalPath = iosKeyguardDataDirectory()
        .resolve("pending_uploads", accountId, namespace)
}
