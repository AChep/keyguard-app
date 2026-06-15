package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface ArchiveCipherById : (
    Set<String>,
) -> IO<Unit>
