package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.copy.atomicDataDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PendingUploadDirProviderDesktop(
    private val dataDirectory: DataDirectory,
) : PendingUploadDirProvider {
    constructor(
        directDI: DirectDI,
    ) : this(
        dataDirectory = directDI.instance(),
    )

    override suspend fun get(
        accountId: String,
        namespace: String,
    ) = dataDirectory
        .atomicDataDirectory()
        .resolveDirectory(AtomicPathComponent.parse(namespace))
        .resolveDirectory(AtomicPathComponent.parse(accountId))
}
