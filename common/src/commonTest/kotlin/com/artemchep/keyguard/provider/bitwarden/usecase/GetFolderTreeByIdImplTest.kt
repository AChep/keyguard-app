package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * The item-detail breadcrumb. Its candidate set is every folder of the account
 * regardless of hierarchy mode, so the mode filter has to live in the tree use
 * case — otherwise a path folder adopts an unrelated same-named group.
 */
class GetFolderTreeByIdImplTest {
    private val accountId = "acc-1"

    @Test
    fun `the breadcrumb of a path folder never crosses into another hierarchy mode`() = runTest {
        val useCase = GetFolderTreeByIdImpl(
            getFolders = FakeGetFolders(
                listOf(
                    folder(id = "g", name = "Personal", hierarchyMode = FolderHierarchyMode.ParentId),
                    folder(id = "b", name = "Personal/Bank"),
                ),
            ),
            getFolderTree = GetFolderTreeImpl(),
        )

        val tree = assertNotNull(useCase.invoke("b").first())
        assertEquals(listOf("Personal/Bank"), tree.hierarchy.map { it.name })
        assertEquals(listOf("b"), tree.hierarchy.map { it.folder.id })
    }

    @Test
    fun `a same-mode path parent is still an ancestor`() = runTest {
        val useCase = GetFolderTreeByIdImpl(
            getFolders = FakeGetFolders(
                listOf(
                    folder(id = "p", name = "Personal"),
                    folder(id = "b", name = "Personal/Bank"),
                ),
            ),
            getFolderTree = GetFolderTreeImpl(),
        )

        val tree = assertNotNull(useCase.invoke("b").first())
        assertEquals(listOf("Personal", "Bank"), tree.hierarchy.map { it.name })
    }

    private fun folder(
        id: String,
        name: String,
        parentId: String? = null,
        hierarchyMode: FolderHierarchyMode = FolderHierarchyMode.Path,
    ) = DFolder(
        id = id,
        accountId = accountId,
        revisionDate = Instant.fromEpochMilliseconds(0),
        service = BitwardenService(
            version = BitwardenService.VERSION,
        ),
        deleted = false,
        synced = true,
        name = name,
        parentId = parentId,
        hierarchyMode = hierarchyMode,
    )

    private class FakeGetFolders(
        private val folders: List<DFolder>,
    ) : GetFolders {
        override fun invoke(): Flow<List<DFolder>> = flowOf(folders)
    }
}
