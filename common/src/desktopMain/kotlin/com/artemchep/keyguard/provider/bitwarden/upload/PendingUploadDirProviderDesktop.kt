package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.resolve
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
    ) = LocalPath(
        dataDirectory.dataBlocking(),
    )
        .resolve(namespace)
        .resolve(accountId)
}
