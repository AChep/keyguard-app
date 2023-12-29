package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface RemoveCipherById : (
    Set<String>,
) -> IO<Unit>
