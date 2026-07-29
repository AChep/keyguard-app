package com.artemchep.keyguard.provider.bitwarden.upload

import android.content.Context
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.toLocalPath
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
        PendingUploadDirectory(
            root = context.filesDir.toLocalPath(),
            relativePath = AtomicRelativePath.fromComponents(
                AtomicPathComponent.parse(namespace),
                AtomicPathComponent.parse(accountId),
            ),
        )
    }
}
