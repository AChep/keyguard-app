package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.CipherId
import com.artemchep.keyguard.common.model.PatchSendRequest

interface MarkWatchtowerAlertAsRead : (
    CipherId,
) -> IO<Unit>
