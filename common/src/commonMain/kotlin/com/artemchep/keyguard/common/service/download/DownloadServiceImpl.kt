package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.model.RemoveAttachmentRequest
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DownloadServiceImpl(
    private val downloadManager: DownloadManager,
) : DownloadService {
    constructor(
        directDI: DirectDI,
    ) : this(
        downloadManager = directDI.instance(),
    )

    override fun download(
        request: DownloadAttachmentRequestData,
    ): IO<Unit> = ioEffect {
        val tag = DownloadInfoEntity2.AttachmentDownloadTag(
            localCipherId = request.localCipherId,
            remoteCipherId = request.remoteCipherId,
            attachmentId = request.attachmentId,
        )
        downloadManager.queue(
            tag = tag,
            url = request.url,
            urlIsOneTime = request.urlIsOneTime,
            name = request.name,
            key = request.encryptionKey,
            worker = true,
        )
    }

    override fun remove(
        request: RemoveAttachmentRequest,
    ): IO<Unit> = ioEffect {
        when (request) {
            is RemoveAttachmentRequest.ByDownloadId -> request.handle()
            is RemoveAttachmentRequest.ByLocalCipherAttachment -> request.handle()
        }
    }

    private suspend fun RemoveAttachmentRequest.ByDownloadId.handle() {
        downloadManager.removeByDownloadId(downloadId)
    }

    private suspend fun RemoveAttachmentRequest.ByLocalCipherAttachment.handle() {
        val ref = DownloadInfoEntity2.AttachmentDownloadTag(
            localCipherId = localCipherId,
            remoteCipherId = remoteCipherId,
            attachmentId = attachmentId,
        )
        downloadManager.removeByTag(tag = ref)
    }
}
