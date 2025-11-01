package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.model.RemoveAttachmentRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.keepass.KeePassUtil
import com.artemchep.keyguard.common.service.keepass.generateAttachmentUrl
import com.artemchep.keyguard.common.service.text.Base32Service
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DownloadServiceImpl(
    private val downloadManager: DownloadManager,
    private val cryptoGenerator: CryptoGenerator,
    private val base32Service: Base32Service,
) : DownloadService {
    constructor(
        directDI: DirectDI,
    ) : this(
        downloadManager = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base32Service = directDI.instance(),
    )

    override fun download(
        request: DownloadAttachmentRequestData,
    ): IO<Unit> = ioEffect {
        val tag = DownloadInfoEntity2.AttachmentDownloadTag(
            localCipherId = request.localCipherId,
            remoteCipherId = request.remoteCipherId,
            attachmentId = request.attachmentId,
        )

        val url: String
        val urlIsOneTime: Boolean
        val data: ByteArray?
        when (val source = request.source) {
            is DownloadAttachmentRequestData.DirectSource -> {
                // We want to encode the data in the url, so the file loader
                // that expects the URL to be unique per file doesn't break.
                url = KeePassUtil.generateAttachmentUrl(
                    data = source.data,
                    cryptoGenerator = cryptoGenerator,
                    base32Service = base32Service,
                )
                urlIsOneTime = true
                data = source.data
            }
            is DownloadAttachmentRequestData.UrlSource -> {
                url = source.url
                urlIsOneTime = source.urlIsOneTime
                data = null
            }
        }
        downloadManager.queue(
            tag = tag,
            url = url,
            urlIsOneTime = urlIsOneTime,
            data = data,
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
