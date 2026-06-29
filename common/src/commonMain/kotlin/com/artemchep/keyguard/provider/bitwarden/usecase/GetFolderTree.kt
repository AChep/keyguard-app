package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DFolderTree2
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.usecase.GetFolderTree
import com.artemchep.keyguard.common.util.createFolderHierarchy
import org.kodein.di.DirectDI

/**
 * @author Artem Chepurnyi
 */
class GetFolderTreeImpl() : GetFolderTree {
    companion object {
        private const val TAG = "GetFolderTree.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
    )

    override fun <T : Any> invoke(
        lens: (T) -> String,
        allFolders: Collection<T>,
        folder: T,
        id: (T) -> String,
        parentId: (T) -> String?,
        hierarchyMode: FolderHierarchyMode,
    ): DFolderTree2<T> {
        val hierarchy = createFolderHierarchy(
            lens = lens,
            allFolders = allFolders,
            folder = folder,
            id = id,
            parentId = parentId,
            hierarchyMode = hierarchyMode,
        )
        return DFolderTree2(
            folder = hierarchy.folder,
            hierarchy = hierarchy.nodes
                .map { node ->
                    DFolderTree2.Node(
                        name = node.name,
                        folder = node.folder,
                    )
                },
        )
    }
}
