package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface RemoveUrlOverrideById : (
    Set<String>,
) -> IO<Unit>
