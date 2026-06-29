package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DFolderTree2
import com.artemchep.keyguard.common.model.FolderHierarchyMode

interface GetFolderTree {
    fun <T : Any> invoke(
        lens: (T) -> String,
        allFolders: Collection<T>,
        folder: T,
        id: (T) -> String = lens,
        parentId: (T) -> String? = { null },
        hierarchyMode: FolderHierarchyMode = FolderHierarchyMode.Path,
    ): DFolderTree2<T>
}
