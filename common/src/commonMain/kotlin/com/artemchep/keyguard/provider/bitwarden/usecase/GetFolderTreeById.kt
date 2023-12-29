package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DFolderTree
import com.artemchep.keyguard.common.usecase.GetFolderTree
import com.artemchep.keyguard.common.usecase.GetFolderTreeById
import com.artemchep.keyguard.common.usecase.GetFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

/**
 * @author Artem Chepurnyi
 */
class GetFolderTreeByIdImpl(
    private val getFolders: GetFolders,
    private val getFolderTree: GetFolderTree,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetFolderTreeById {
    companion object {
        private const val TAG = "GetFolderTreeById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        getFolders = directDI.instance(),
        getFolderTree = directDI.instance(),
    )

    override fun invoke(
        folderId: String,
    ): Flow<DFolderTree?> = getFolders()
        .map { allFolders ->
            // First start with finding a target folder,
            // we will later use it to form the hierarchy.
            val target = allFolders.firstOrNull { it.id == folderId }
                ?: return@map null
            val folders = allFolders
                .filter { it.accountId == target.accountId }

            val tree = getFolderTree.invoke(
                lens = { it.name },
                folders,
                target,
            )
            DFolderTree(
                folder = target,
                hierarchy = tree
                    .hierarchy
                    .map {
                        DFolderTree.Node(
                            name = it.name,
                            folder = it.folder,
                        )
                    },
            )
        }
}
