package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface RetryCipher : (
    Set<String>,
) -> IO<Unit>
