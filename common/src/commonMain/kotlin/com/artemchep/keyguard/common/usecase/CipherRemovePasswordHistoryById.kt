package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface CipherRemovePasswordHistoryById : (
    String,
    List<String>,
) -> IO<Unit>
