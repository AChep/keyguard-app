package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DFolderTree2
import com.artemchep.keyguard.common.usecase.GetFolderTree
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import kotlin.coroutines.CoroutineContext

/**
 * @author Artem Chepurnyi
 */
class GetFolderTreeImpl(
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetFolderTree {
    companion object {
        private const val TAG = "GetFolderTree.bitwarden"

        private const val DELIMITER = '/'
    }

    private val trimPrefixRegex = "^\\s*/\\s*".toRegex()

    constructor(directDI: DirectDI) : this(
    )

    override fun <T : Any> invoke(
        lens: (T) -> String,
        allFolders: Collection<T>,
        folder: T,
    ): DFolderTree2<T> {
        // First start with finding a target folder,
        // we will later use it to form the hierarchy.
        val target = folder
        val folders = allFolders

        val rawHierarchy = target.hierarchyIn(lens, folders)
            .toList()
            .asReversed()
        val hierarchy = rawHierarchy
            .mapIndexed { index, folder ->
                val name = if (index > 0) {
                    // Slice the name basing on the
                    // parent folder.
                    val parent = rawHierarchy[index - 1]
                    lens(folder).substringAfter(lens(parent))
                        .replace(trimPrefixRegex, "")
                } else {
                    lens(folder)
                }
                DFolderTree2.Node(
                    name = name,
                    folder = folder,
                )
            }
        return DFolderTree2<T>(
            folder = target,
            hierarchy = hierarchy,
        )
    }

    private fun <T> T.hierarchyIn(
        lens: (T) -> String,
        folders: Collection<T>,
    ) = generateSequence(this) {
        findParentOrNull(
            lens = lens,
            folders = folders,
            folder = it,
        )
    }

    private fun <T> findParentOrNull(
        lens: (T) -> String,
        folders: Collection<T>,
        folder: T,
    ) = findParentByNameOrNull(
        lens = lens,
        folders = folders,
        name = lens(folder),
    )

    private fun <T> findParentByNameOrNull(
        lens: (T) -> String,
        folders: Collection<T>,
        name: String,
    ): T? {
        val index = name.indexOfLast { it == DELIMITER }
        if (index == -1) {
            // The name does not contain the hierarchy
            // symbol, stop searching for the matches.
            return null
        }

        val parentName = name.substring(0, index)
        // Try to find a directory that matches
        // parent by the name.
        return folders
            .firstOrNull { lens(it) == parentName }
        // Otherwise search one level deeper.
            ?: findParentByNameOrNull(
                lens = lens,
                folders = folders,
                name = parentName,
            )
    }
}
