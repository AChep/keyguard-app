package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.combine
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.RemoveAttachmentRequest
import com.artemchep.keyguard.common.service.download.DownloadService
import com.artemchep.keyguard.common.usecase.RemoveAttachment

/**
 * @author Artem Chepurnyi
 */
class RemoveAttachmentImpl(
    private val downloadService: DownloadService,
) : RemoveAttachment {
    companion object {
        private const val THREAD_BUCKET_SIZE = 10
    }

    override fun invoke(
        requests: List<RemoveAttachmentRequest>,
    ): IO<Unit> = requests
        .map { request ->
            downloadService.remove(request)
        }
        .combine(bucket = THREAD_BUCKET_SIZE)
        .map { Unit }
}
