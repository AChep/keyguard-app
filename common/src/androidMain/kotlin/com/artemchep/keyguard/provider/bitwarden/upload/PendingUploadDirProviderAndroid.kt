package com.artemchep.keyguard.provider.bitwarden.upload

import android.content.Context
import com.artemchep.keyguard.platform.toLocalPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PendingUploadDirProviderAndroid(
    private val context: Context,
) : PendingUploadDirProvider {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance(),
    )

    override suspend fun get(
        accountId: String,
        namespace: String,
    ) = withContext(Dispatchers.IO) {
        context.filesDir
            .resolve(namespace)
            .resolve(accountId)
            .toLocalPath()
    }
}
