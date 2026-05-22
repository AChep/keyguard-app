package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AttachmentPreviewPayload
import com.artemchep.keyguard.common.model.AttachmentPreviewRequest

interface GetAttachmentPreview : (AttachmentPreviewRequest) -> IO<AttachmentPreviewPayload>
