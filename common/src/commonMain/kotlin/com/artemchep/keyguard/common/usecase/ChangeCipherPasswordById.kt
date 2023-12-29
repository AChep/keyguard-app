package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface ChangeCipherPasswordById : (
    Map<String, String>,
) -> IO<Unit>
