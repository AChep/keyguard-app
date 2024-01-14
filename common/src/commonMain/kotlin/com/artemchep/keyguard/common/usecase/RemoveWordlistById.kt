package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface RemoveWordlistById : (
    Set<Long>,
) -> IO<Unit>
