package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface RenameFolderById : (
    Map<String, String>,
) -> IO<Unit>
