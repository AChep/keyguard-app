package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.usecase.CleanUpAttachment

object CleanUpAttachmentIos : CleanUpAttachment {
    override fun invoke(): IO<Int> = io(0)
}
