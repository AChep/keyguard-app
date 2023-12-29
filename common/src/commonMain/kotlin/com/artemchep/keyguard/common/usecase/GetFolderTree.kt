package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DFolderTree2

interface GetFolderTree {
    fun <T : Any> invoke(
        lens: (T) -> String,
        allFolders: Collection<T>,
        folder: T,
    ): DFolderTree2<T>
}
