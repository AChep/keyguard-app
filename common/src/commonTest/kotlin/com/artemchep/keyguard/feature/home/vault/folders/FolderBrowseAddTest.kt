package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.FolderHierarchyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderBrowseAddTest {
    @Test
    fun `path mode with non-empty parent joins parent and leaf with delimiter`() {
        val request = FoldersRoute.Args.Parent.Path(
            accountId = accountId,
            path = "Work",
        ).createAddFolderRequest(name = "Clients")

        assertEquals(accountId, request.accountId)
        assertEquals("Work/Clients", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode joins nested parent and leaf with delimiter`() {
        val request = FoldersRoute.Args.Parent.Path(
            accountId = accountId,
            path = "Work/Clients",
        ).createAddFolderRequest(name = "Acme")

        assertEquals("Work/Clients/Acme", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode with blank parent uses leaf only`() {
        val request = FoldersRoute.Args.Parent.Path(
            accountId = accountId,
            path = "",
        ).createAddFolderRequest(name = "Work")

        assertEquals("Work", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode with whitespace parent uses leaf only`() {
        val request = FoldersRoute.Args.Parent.Path(
            accountId = accountId,
            path = "   ",
        ).createAddFolderRequest(name = "Work")

        assertEquals("Work", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode strips delimiter from leaf so it cannot introduce extra levels`() {
        val request = FoldersRoute.Args.Parent.Path(
            accountId = accountId,
            path = "Work",
        ).createAddFolderRequest(name = "A/B")

        assertEquals("Work/AB", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode strips multiple delimiters from leaf`() {
        val request = FoldersRoute.Args.Parent.Path(
            accountId = accountId,
            path = "Work",
        ).createAddFolderRequest(name = "/A/B/C/")

        assertEquals("Work/ABC", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `path mode with blank parent and delimiter-only leaf collapses to empty name`() {
        val request = FoldersRoute.Args.Parent.Path(
            accountId = accountId,
            path = "",
        ).createAddFolderRequest(name = "/")

        // The leaf delimiter is stripped, leaving a blank segment that is filtered
        // out of the join, so nothing remains.
        assertEquals("", request.name)
        assertNull(request.parentId)
        assertEquals(FolderHierarchyMode.Path, request.hierarchyMode)
    }

    @Test
    fun `id mode sets parent id and keeps name verbatim`() {
        val request = FoldersRoute.Args.Parent.Id(
            accountId = accountId,
            folderId = "folder-1",
        ).createAddFolderRequest(name = "Clients")

        assertEquals(accountId, request.accountId)
        assertEquals("Clients", request.name)
        assertEquals("folder-1", request.parentId)
        assertEquals(FolderHierarchyMode.ParentId, request.hierarchyMode)
    }

    @Test
    fun `id mode does not strip delimiter from name`() {
        val request = FoldersRoute.Args.Parent.Id(
            accountId = accountId,
            folderId = "folder-1",
        ).createAddFolderRequest(name = "A/B")

        // In id mode names are stored opaquely, so the delimiter is preserved.
        assertEquals("A/B", request.name)
        assertEquals("folder-1", request.parentId)
        assertEquals(FolderHierarchyMode.ParentId, request.hierarchyMode)
    }

    private companion object {
        const val accountId = "account"
    }
}
