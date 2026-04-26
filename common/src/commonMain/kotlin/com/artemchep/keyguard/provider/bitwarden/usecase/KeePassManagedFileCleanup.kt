package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.platform.recordException

internal fun cleanupManagedKeePassFiles(
    fileService: FileService,
    tokens: Iterable<ServiceToken>,
) {
    tokens.asSequence()
        .filterIsInstance<KeePassToken>()
        .filter { it.files.managedByApp }
        .forEach { token ->
            deleteManagedKeePassFile(
                fileService = fileService,
                databaseUri = token.files.databaseUri,
            )
        }
}

private fun deleteManagedKeePassFile(
    fileService: FileService,
    databaseUri: String,
) {
    runCatching {
        fileService.delete(databaseUri)
        databaseUri.parentUriOrNull()
            ?.let(fileService::delete)
    }.onFailure(::recordException)
}

internal fun String.parentUriOrNull(): String? {
    val index = lastIndexOf('/')
    if (index <= "file://".length) {
        return null
    }

    return substring(0, index)
}
