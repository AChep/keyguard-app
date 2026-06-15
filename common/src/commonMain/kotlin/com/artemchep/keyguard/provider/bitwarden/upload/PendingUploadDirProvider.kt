package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.platform.LocalPath

interface PendingUploadDirProvider {
    suspend fun get(
        accountId: String,
        namespace: String,
    ): LocalPath
}
