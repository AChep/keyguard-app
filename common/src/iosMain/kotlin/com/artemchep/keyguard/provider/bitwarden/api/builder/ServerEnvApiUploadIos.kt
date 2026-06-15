package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileUploadTarget
import io.ktor.client.HttpClient

internal actual suspend fun platformUploadFileToTargetDirect(
    httpClient: HttpClient,
    env: ServerEnv,
    token: String,
    target: SendFileUploadTarget,
    fileName: String,
    filePath: String,
    fileLength: Long,
    route: String,
) {
    throw UnsupportedOperationException("File uploads are not supported on this platform.")
}

internal actual suspend fun platformUploadFileToTargetAzure(
    httpClient: HttpClient,
    env: ServerEnv,
    target: SendFileUploadTarget,
    filePath: String,
    fileLength: Long,
    route: String,
) {
    throw UnsupportedOperationException("File uploads are not supported on this platform.")
}
