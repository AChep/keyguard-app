package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent

interface PendingUploadDirProvider {
    suspend fun get(
        accountId: String,
        namespace: String,
    ): PendingUploadDirectory
}

typealias PendingUploadDirectory = AtomicDirectoryDestination

fun PendingUploadDirectory.destination(
    fileName: AtomicPathComponent,
): AtomicFileDestination = resolve(fileName)
