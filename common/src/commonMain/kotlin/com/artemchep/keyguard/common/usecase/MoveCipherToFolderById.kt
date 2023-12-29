package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.FolderOwnership2

interface MoveCipherToFolderById : (
    Set<String>,
    FolderOwnership2,
) -> IO<Unit>
