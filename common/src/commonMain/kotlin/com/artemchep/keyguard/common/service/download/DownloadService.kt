package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.model.RemoveAttachmentRequest

interface DownloadService {
    fun download(
        request: DownloadAttachmentRequestData,
    ): IO<Unit>

    fun remove(
        request: RemoveAttachmentRequest,
    ): IO<Unit>
}
