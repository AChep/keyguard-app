package com.artemchep.keyguard.common.service.download

import arrow.core.left
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

internal class BitwardenAttachmentSourceLoader(
    private val httpClientProvider: BitwardenAttachmentHttpClientProvider,
    private val fileEncryptionCodec: FileEncryptionCodec,
    private val stagingSpoolFactory: StagingSpoolFactory,
) {
    fun fileLoader(
        request: DownloadAttachmentRequestData,
        source: DownloadAttachmentRequestData.UrlSource,
        writer: DownloadWriter,
    ): Flow<DownloadProgress> = flow {
        val task = DownloadTaskImpl(
            client = httpClientProvider(request.localCipherId, source.url),
            fileEncryptionCodec = fileEncryptionCodec,
            stagingSpoolFactory = stagingSpoolFactory,
        )
        emitAll(task.fileLoader(source.url, request.encryptionKey, writer))
    }.catch { error ->
        error.throwIfFatalOrCancellation()
        emit(DownloadProgress.Complete(error.left()))
    }
}
