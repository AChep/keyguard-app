package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class FolderBrowseAddTest {
    @Test
    fun `keepass root preserves parent id mode for an offline child`() {
        val rootRequest = createRootAddFolderRequest(
            accountId = accountId,
            name = "Parent",
            hierarchyMode = FolderHierarchyMode.ParentId,
        )

        assertEquals("Parent", rootRequest.name)
        assertNull(rootRequest.parentId)
        assertEquals(FolderHierarchyMode.ParentId, rootRequest.hierarchyMode)

        val childRequest = folder(
            id = "root-id",
            name = rootRequest.name,
            hierarchyMode = rootRequest.hierarchyMode,
        ).createAddFolderRequest(name = "Child")

        assertEquals("Child", childRequest.name)
        assertEquals("root-id", childRequest.parentId)
        assertEquals(FolderHierarchyMode.ParentId, childRequest.hierarchyMode)
    }

    @Test
    fun `bitwarden root preserves path mode for an offline child`() {
        val rootRequest = createRootAddFolderRequest(
            accountId = accountId,
            name = "Parent",
            hierarchyMode = FolderHierarchyMode.Path,
        )

        assertEquals("Parent", rootRequest.name)
        assertNull(rootRequest.parentId)
        assertEquals(FolderHierarchyMode.Path, rootRequest.hierarchyMode)

        val childRequest = folder(
            id = "root-id",
            name = rootRequest.name,
            hierarchyMode = rootRequest.hierarchyMode,
        ).createAddFolderRequest(name = "Child")

        assertEquals("Parent/Child", childRequest.name)
        assertNull(childRequest.parentId)
        assertEquals(FolderHierarchyMode.Path, childRequest.hierarchyMode)
    }

    @Test
    fun `path mode with non-empty parent joins parent and leaf with delimiter`() {
        val request = folder(name = "Work")
            .createAddFolderRequest(name = "Clients")

        assertEquals(accountId, request.accountId)
        assertEquals("Work/Clients", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode joins nested parent and leaf with delimiter`() {
        val request = folder(name = "Work/Clients")
            .createAddFolderRequest(name = "Acme")

        assertEquals("Work/Clients/Acme", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode with blank parent uses leaf only`() {
        val request = folder(name = "")
            .createAddFolderRequest(name = "Work")

        assertEquals("Work", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode with whitespace parent uses leaf only`() {
        val request = folder(name = "   ")
            .createAddFolderRequest(name = "Work")

        assertEquals("Work", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode strips delimiter from leaf so it cannot introduce extra levels`() {
        val request = folder(name = "Work")
            .createAddFolderRequest(name = "A/B")

        assertEquals("Work/AB", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode strips multiple delimiters from leaf`() {
        val request = folder(name = "Work")
            .createAddFolderRequest(name = "/A/B/C/")

        assertEquals("Work/ABC", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode with blank parent and delimiter-only leaf collapses to empty name`() {
        val request = folder(name = "")
            .createAddFolderRequest(name = "/")

        // The leaf delimiter is stripped, leaving a blank segment that is filtered
        // out of the join, so nothing remains.
        assertEquals("", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `id mode sets parent id and keeps name verbatim`() {
        val request = folder(
            id = "folder-1",
            name = "Parent",
            hierarchyMode = FolderHierarchyMode.ParentId,
        ).createAddFolderRequest(name = "Clients")

        assertEquals(accountId, request.accountId)
        assertEquals("Clients", request.name)
        assertEquals("folder-1", request.parentId)
        assertEquals(FolderHierarchyMode.ParentId, request.hierarchyMode)
    }

    @Test
    fun `id mode does not strip delimiter from name`() {
        val request = folder(
            id = "folder-1",
            name = "Parent",
            hierarchyMode = FolderHierarchyMode.ParentId,
        ).createAddFolderRequest(name = "A/B")

        // In id mode names are stored opaquely, so the delimiter is preserved.
        assertEquals("A/B", request.name)
        assertEquals("folder-1", request.parentId)
        assertEquals(FolderHierarchyMode.ParentId, request.hierarchyMode)
    }

    private fun folder(
        name: String,
        id: String = "folder",
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
        hierarchyMode = hierarchyMode,
    )

    private companion object {
        const val accountId = "account"
    }
}
