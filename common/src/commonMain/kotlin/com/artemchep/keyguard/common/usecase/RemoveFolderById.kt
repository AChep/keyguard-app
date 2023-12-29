package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface RemoveFolderById : (
    Set<String>,
    RemoveFolderById.OnCiphersConflict,
) -> IO<Unit> {
    enum class OnCiphersConflict {
        IGNORE,
        TRASH,
    }
}
