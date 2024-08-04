package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.combine
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.service.download.DownloadService
import com.artemchep.keyguard.common.usecase.DownloadAttachment
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class DownloadAttachmentImpl2(
    private val downloadAttachmentMetadata: DownloadAttachmentMetadata,
    private val downloadService: DownloadService,
) : DownloadAttachment {
    companion object {
        private const val THREAD_BUCKET_SIZE = 10
    }

    constructor(directDI: DirectDI) : this(
        downloadAttachmentMetadata = directDI.instance(),
        downloadService = directDI.instance(),
    )

    override fun invoke(
        requests: List<DownloadAttachmentRequest>,
    ): IO<Unit> = requests
        .map { request ->
            downloadAttachmentMetadata(request)
                .flatMap(downloadService::download)
        }
        .combine(bucket = THREAD_BUCKET_SIZE)
        .map { Unit }
}
