package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.combine
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.RemoveAttachmentRequest
import com.artemchep.keyguard.common.service.download.DownloadService
import com.artemchep.keyguard.common.usecase.RemoveAttachment
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveAttachmentImpl(
    private val downloadService: DownloadService,
) : RemoveAttachment {
    companion object {
        private const val THREAD_BUCKET_SIZE = 10
    }

    constructor(directDI: DirectDI) : this(
        downloadService = directDI.instance(),
    )

    override fun invoke(
        requests: List<RemoveAttachmentRequest>,
    ): IO<Unit> = requests
        .map { request ->
            downloadService.remove(request)
        }
        .combine(bucket = THREAD_BUCKET_SIZE)
        .map { Unit }
}
