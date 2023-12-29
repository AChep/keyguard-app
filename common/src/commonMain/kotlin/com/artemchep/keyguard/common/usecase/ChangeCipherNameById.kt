package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface ChangeCipherNameById : (
    Map<String, String>,
) -> IO<Unit>
