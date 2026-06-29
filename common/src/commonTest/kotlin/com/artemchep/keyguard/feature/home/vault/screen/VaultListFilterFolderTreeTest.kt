package com.artemchep.keyguard.feature.home.vault.screen

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Characterization tests for [buildFolderFilterTree]. These pin the observable
 * behavior of the vault list folder filter — cross-account same-path merging,
 * cipher-membership selectability, descendant-driven expandability, depth, and
 * node/parent identity — so that the underlying tree construction can be
 * refactored without changing what the filter shows.
 */
class VaultListFilterFolderTreeTest {
    @Test
    fun `leaf folder with ciphers is selectable and flat`() {
        val work = folder(id = "work", name = "Work")
        val tree = buildFolderFilterTree(
            folders = listOf(work),
            folderIdsWithCiphers = setOf("work"),
        )
        val node = tree.nodes.single()
        assertEquals(listOf("Work"), node.path)
        assertEquals("Work", node.title)
        assertEquals(0, node.depth)
        assertEquals(setOf("work"), node.folderIds)
        assertTrue(node.selectable)
        assertFalse(node.expandable)
        assertNull(node.parentNodeId)
        // A single depth-0 selectable node does not trigger the nested UI.
        assertFalse(tree.useNestedUi)
    }

    @Test
    fun `parent with a selectable descendant is kept and expandable`() {
        val work = folder(id = "work", name = "Work")
        val clients = folder(id = "clients", name = "Work/Clients")
        val tree = buildFolderFilterTree(
            folders = listOf(work, clients),
            // Only the child holds ciphers; the parent does not.
            folderIdsWithCiphers = setOf("clients"),
        )
        // Sorted by the joined path: "Work" before "Work/Clients".
        assertEquals(
            listOf(listOf("Work"), listOf("Work", "Clients")),
            tree.nodes.map { it.path },
        )

        val parent = tree.nodes.first { it.path == listOf("Work") }
        assertFalse(parent.selectable)
        assertTrue(parent.expandable)
        assertEquals(0, parent.depth)
        assertNull(parent.parentNodeId)

        val child = tree.nodes.first { it.path == listOf("Work", "Clients") }
        assertTrue(child.selectable)
        assertFalse(child.expandable)
        assertEquals(1, child.depth)
        // The child links to the parent node.
        assertEquals(parent.nodeId, child.parentNodeId)

        // A selectable node deeper than the root turns on the nested UI.
        assertTrue(tree.useNestedUi)
    }

    @Test
    fun `same name folders in different accounts merge into one node`() {
        val personalA = folder(id = "pA", accountId = "accA", name = "Personal")
        val personalB = folder(id = "pB", accountId = "accB", name = "Personal")
        val tree = buildFolderFilterTree(
            folders = listOf(personalA, personalB),
            // Only one of the merged folders holds ciphers.
            folderIdsWithCiphers = setOf("pA"),
        )
        val node = tree.nodes.single()
        assertEquals(listOf("Personal"), node.path)
        // Both accounts' folders collapse into the same merged node.
        assertEquals(setOf("pA", "pB"), node.folderIds)
        assertTrue(node.selectable)
    }

    @Test
    fun `node with neither ciphers nor selectable descendants is dropped`() {
        val empty = folder(id = "empty", name = "Empty")
        val tree = buildFolderFilterTree(
            folders = listOf(empty),
            folderIdsWithCiphers = emptySet(),
        )
        assertTrue(tree.nodes.isEmpty())
        assertFalse(tree.useNestedUi)
    }

    @Test
    fun `only root level selectable nodes keep the flat UI`() {
        val a = folder(id = "a", name = "Alpha")
        val b = folder(id = "b", name = "Beta")
        val tree = buildFolderFilterTree(
            folders = listOf(a, b),
            folderIdsWithCiphers = setOf("a", "b"),
        )
        assertEquals(listOf("Alpha", "Beta"), tree.nodes.map { it.title })
        assertTrue(tree.nodes.all { it.selectable && it.depth == 0 })
        assertFalse(tree.useNestedUi)
    }

    private fun folder(
        id: String,
        accountId: String = Companion.accountId,
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

    private companion object {
        const val accountId = "account"
    }
}
