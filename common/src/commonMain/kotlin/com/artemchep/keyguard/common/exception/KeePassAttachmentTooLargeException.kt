package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.KEEPASS_FILE_UPLOAD_TOO_LARGE_MESSAGE
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

class KeePassAttachmentTooLargeException : IllegalArgumentException(
    KEEPASS_FILE_UPLOAD_TOO_LARGE_MESSAGE,
), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.exportaccount_kdbx_attachment_too_large)
}
