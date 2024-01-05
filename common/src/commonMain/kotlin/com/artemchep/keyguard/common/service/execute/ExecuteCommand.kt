package com.artemchep.keyguard.common.service.execute

import com.artemchep.keyguard.common.io.IO

interface ExecuteCommand : (String) -> IO<Unit> {
    val interpreter: String?
}
