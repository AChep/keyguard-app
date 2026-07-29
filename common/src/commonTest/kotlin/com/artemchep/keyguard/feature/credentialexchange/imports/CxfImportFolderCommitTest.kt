package com.artemchep.keyguard.feature.credentialexchange.imports

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddFolderRequest
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The folder half of the CXF import commit.
 *
 * The property under test is that the imported hierarchy is written in a
 * representation the target account's sync can actually persist. Writing
 * `ParentId` rows into a Bitwarden account looks correct until the first sync
 * rebuilds every folder row out of a wire format that carries only `name`, at
 * which point the whole tree flattens and same-named leaves of different
 * branches merge — silently, because the items themselves survive.
 */
class CxfImportFolderCommitTest {
    private class RecordingAddFolder : AddFolder {
        val batches = mutableListOf<List<AddFolderRequest>>()

        override fun invoke(requests: Collection<AddFolderRequest>): IO<List<String>> = {
            batches += requests.toList()
            requests.indices.map { index -> "id-${batches.size}-$index" }
        }
    }

    private val accountId = AccountId("account-1")

    /**
     * `Work > Reports` and `Personal > Reports`: two same-named leaves in
     * different branches, the shape that merges into one node once the parent
     * edge is gone.
     */
    private val plan = listOf(
        CxfImportPlan.Folder(key = "k0", parentKey = null, title = "Work"),
        CxfImportPlan.Folder(key = "k1", parentKey = "k0", title = "Reports"),
        CxfImportPlan.Folder(key = "k2", parentKey = null, title = "Personal"),
        CxfImportPlan.Folder(key = "k3", parentKey = "k2", title = "Reports"),
    )

    @Test
    fun `a bitwarden account gets path folders carrying the whole ancestor path`() = runTest {
        val addFolder = RecordingAddFolder()
        val ids = createPlannedFolders(
            folders = plan,
            accountId = accountId,
            addFolder = addFolder,
            hierarchyMode = cxfFolderHierarchyMode(AccountType.BITWARDEN),
        )

        val requests = addFolder.batches.flatten()
        assertEquals(
            listOf("Work", "Personal", "Work/Reports", "Personal/Reports"),
            requests.map { it.name },
        )
        // Nothing may rely on the parent edge: the Bitwarden folder wire format
        // has no member for it, so the next sync drops it.
        assertEquals(
            List(requests.size) { FolderHierarchyMode.Path },
            requests.map { it.hierarchyMode },
        )
        assertEquals(List(requests.size) { null }, requests.map { it.parentId })
        // The two "Reports" leaves stay distinguishable once the ancestor path is
        // joined into the name.
        assertEquals(4, requests.map { it.name }.toSet().size)
        assertEquals(setOf("k0", "k1", "k2", "k3"), ids.keys)
    }

    @Test
    fun `a keepass account keeps parent-id folders with leaf titles`() = runTest {
        val addFolder = RecordingAddFolder()
        createPlannedFolders(
            folders = plan,
            accountId = accountId,
            addFolder = addFolder,
            hierarchyMode = cxfFolderHierarchyMode(AccountType.KEEPASS),
        )

        val requests = addFolder.batches.flatten()
        assertEquals(
            listOf("Work", "Personal", "Reports", "Reports"),
            requests.map { it.name },
        )
        assertEquals(
            List(requests.size) { FolderHierarchyMode.ParentId },
            requests.map { it.hierarchyMode },
        )
        // Children resolve against the ids their parents were created with.
        assertEquals(listOf(null, null, "id-1-0", "id-1-1"), requests.map { it.parentId })
    }

    @Test
    fun `an unresolvable account falls back to the representation that survives`() {
        assertEquals(FolderHierarchyMode.Path, cxfFolderHierarchyMode(null))
    }

    @Test
    fun `a root path title reaches the add-folder request intact`() = runTest {
        val addFolder = RecordingAddFolder()
        createPlannedFolders(
            folders = listOf(
                // The source had no real "A" folder row, so its exporter kept
                // the entire path in this root collection's title.
                CxfImportPlan.Folder(key = "k0", parentKey = null, title = "A/B"),
            ),
            accountId = accountId,
            addFolder = addFolder,
            hierarchyMode = FolderHierarchyMode.Path,
        )

        assertEquals(listOf("A/B"), addFolder.batches.flatten().map { it.name })
    }

    @Test
    fun `a nested title carrying missing segments reaches the add-folder request intact`() =
        runTest {
            val addFolder = RecordingAddFolder()
            createPlannedFolders(
                folders = listOf(
                    CxfImportPlan.Folder(key = "k0", parentKey = null, title = "A"),
                    // There was no real "A/B" row, so the child collection
                    // carries both remaining path segments.
                    CxfImportPlan.Folder(key = "k1", parentKey = "k0", title = "B/C"),
                ),
                accountId = accountId,
                addFolder = addFolder,
                hierarchyMode = FolderHierarchyMode.Path,
            )

            assertEquals(
                listOf("A", "A/B/C"),
                addFolder.batches.flatten().map { it.name },
            )
        }

    @Test
    fun `a blank path title still gets the defensive fallback`() {
        val resolved = resolvePlannedFolders(
            folders = listOf(
                CxfImportPlan.Folder(key = "k0", parentKey = null, title = " "),
            ),
            hierarchyMode = FolderHierarchyMode.Path,
        )

        assertEquals(listOf("Folder"), resolved.map { it.name })
    }
}
