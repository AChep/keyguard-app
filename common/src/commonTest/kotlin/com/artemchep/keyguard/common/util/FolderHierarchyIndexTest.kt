package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.common.model.FolderHierarchyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderHierarchyIndexTest {
    private data class Folder(
        val accountId: String,
        val id: String,
        val parentId: String?,
        val path: String,
        val mode: FolderHierarchyMode,
    )

    private fun index(
        folders: Collection<Folder>,
    ): FolderHierarchyIndex<Folder> = createFolderHierarchyIndex(
        folders = folders,
        accountId = { it.accountId },
        lens = { it.path },
        id = { it.id },
        parentId = { it.parentId },
        hierarchyMode = { it.mode },
    )

    private fun parentIdFolder(
        id: String,
        parentId: String?,
        accountId: String = "acc",
    ) = Folder(
        accountId = accountId,
        id = id,
        parentId = parentId,
        // The path is unused in ParentId mode, but keep it distinct so a stray
        // Path-mode collapse would be obvious.
        path = id,
        mode = FolderHierarchyMode.ParentId,
    )

    private fun pathFolder(
        path: String,
        accountId: String = "acc",
        id: String = path,
    ) = Folder(
        accountId = accountId,
        id = id,
        parentId = null,
        path = path,
        mode = FolderHierarchyMode.Path,
    )

    private fun idKey(
        folderId: String,
        accountId: String = "acc",
    ) = FolderHierarchyKey.Id(
        accountId = accountId,
        folderId = folderId,
    )

    private fun pathKey(
        path: String,
        accountId: String = "acc",
    ) = FolderHierarchyKey.Path(
        accountId = accountId,
        path = path,
    )

    @Test
    fun `parent-id cycle of two nodes re-roots both`() {
        // A.parent = B, B.parent = A.
        val a = parentIdFolder(id = "A", parentId = "B")
        val b = parentIdFolder(id = "B", parentId = "A")
        val index = index(listOf(a, b))

        val rootIds = index.childrenOf(null)
            .map { (it.key as FolderHierarchyKey.Id).folderId }
            .toSet()
        // Neither node may be dropped; both surface at the root.
        assertEquals(setOf("A", "B"), rootIds)
        assertNull(index.node(idKey("A"))!!.parentKey)
        assertNull(index.node(idKey("B"))!!.parentKey)

        // Both nodes are re-rooted, so neither is the other's descendant; each
        // node's descendant set is just itself, and descendantsOf terminates.
        val descendantsOfA = index.descendantsOf(idKey("A"))
        assertEquals(setOf(a), descendantsOfA.toSet())
        assertEquals(descendantsOfA.size, descendantsOfA.distinct().size)

        val descendantsOfB = index.descendantsOf(idKey("B"))
        assertEquals(setOf(b), descendantsOfB.toSet())
        assertEquals(descendantsOfB.size, descendantsOfB.distinct().size)
    }

    @Test
    fun `self-parent re-roots the node`() {
        val a = parentIdFolder(id = "A", parentId = "A")
        val index = index(listOf(a))

        val roots = index.childrenOf(null)
        assertEquals(listOf(idKey("A")), roots.map { it.key })
        assertNull(index.node(idKey("A"))!!.parentKey)

        // descendantsOf must terminate; the node appears exactly once.
        val descendants = index.descendantsOf(idKey("A"))
        assertEquals(listOf(a), descendants)
    }

    @Test
    fun `dangling parent re-roots the node`() {
        // A points at a parent id that does not exist in the account.
        val a = parentIdFolder(id = "A", parentId = "missing")
        val index = index(listOf(a))

        assertEquals(listOf(idKey("A")), index.childrenOf(null).map { it.key })
        assertNull(index.node(idKey("A"))!!.parentKey)
        assertEquals(1, index.node(idKey("A"))!!.depth)
    }

    @Test
    fun `cross-account parent re-roots the node`() {
        // A's parent id only exists in a different account, so it is dangling
        // for A's account and must be re-rooted.
        val parentOther = parentIdFolder(id = "P", parentId = null, accountId = "other")
        val a = parentIdFolder(id = "A", parentId = "P", accountId = "acc")
        val index = index(listOf(parentOther, a))

        // A re-roots under its own account; it does not link to "other"'s P.
        assertNull(index.node(idKey("A", accountId = "acc"))!!.parentKey)
        val accRoots = index.childrenOf(null)
            .filter { it.key.accountId == "acc" }
            .map { it.key }
        assertEquals(listOf(idKey("A", accountId = "acc")), accRoots)
    }

    @Test
    fun `per-account scoping keeps identical ids and paths separate`() {
        // Two accounts, same ids and same parent-child shape.
        val acc1Parent = parentIdFolder(id = "P", parentId = null, accountId = "acc1")
        val acc1Child = parentIdFolder(id = "C", parentId = "P", accountId = "acc1")
        val acc2Parent = parentIdFolder(id = "P", parentId = null, accountId = "acc2")
        val acc2Child = parentIdFolder(id = "C", parentId = "P", accountId = "acc2")
        val index = index(listOf(acc1Parent, acc1Child, acc2Parent, acc2Child))

        // acc1's child links to acc1's parent, never acc2's.
        assertEquals(
            idKey("P", accountId = "acc1"),
            index.node(idKey("C", accountId = "acc1"))!!.parentKey,
        )
        assertEquals(
            idKey("P", accountId = "acc2"),
            index.node(idKey("C", accountId = "acc2"))!!.parentKey,
        )

        // Each account has exactly one root, scoped to that account.
        assertEquals(
            listOf(idKey("P", accountId = "acc1")),
            index.childrenOf(null).filter { it.key.accountId == "acc1" }.map { it.key },
        )
        assertEquals(
            listOf(idKey("P", accountId = "acc2")),
            index.childrenOf(null).filter { it.key.accountId == "acc2" }.map { it.key },
        )

        // descendantsOf is per-account: acc1's parent surfaces only acc1 folders.
        assertEquals(
            setOf(acc1Parent, acc1Child),
            index.descendantsOf(idKey("P", accountId = "acc1")).toSet(),
        )
    }

    @Test
    fun `same-path folders collapse into a single node`() {
        // Two distinct folders share the same path within one account.
        val first = pathFolder(path = "a/b", id = "first")
        val second = pathFolder(path = "a/b", id = "second")
        val parent = pathFolder(path = "a", id = "parent")
        val index = index(listOf(parent, first, second))

        val node = index.node(pathKey("a/b"))!!
        // Both folders collapse into the single node's directItems.
        assertEquals(setOf(first, second), node.directItems.toSet())
        // The node's parent is the "a" path node.
        assertEquals(pathKey("a"), node.parentKey)

        // childrenOf de-duplicates the collapsed key.
        val children = index.childrenOf(pathKey("a")).map { it.key }
        assertEquals(listOf(pathKey("a/b")), children)
    }

    @Test
    fun `depth increases monotonically along a parent chain`() {
        val root = parentIdFolder(id = "root", parentId = null)
        val mid = parentIdFolder(id = "mid", parentId = "root")
        val leaf = parentIdFolder(id = "leaf", parentId = "mid")
        val index = index(listOf(root, mid, leaf))

        val rootDepth = index.node(idKey("root"))!!.depth
        val midDepth = index.node(idKey("mid"))!!.depth
        val leafDepth = index.node(idKey("leaf"))!!.depth

        assertEquals(1, rootDepth)
        assertTrue(rootDepth < midDepth, "mid must be deeper than root")
        assertTrue(midDepth < leafDepth, "leaf must be deeper than mid")
        assertEquals(2, midDepth)
        assertEquals(3, leafDepth)
    }

    @Test
    fun `descendantsOf includes own items plus all descendants deduped`() {
        val root = parentIdFolder(id = "root", parentId = null)
        val childA = parentIdFolder(id = "childA", parentId = "root")
        val childB = parentIdFolder(id = "childB", parentId = "root")
        val grandchild = parentIdFolder(id = "grandchild", parentId = "childA")
        val index = index(listOf(root, childA, childB, grandchild))

        val descendantsOfRoot = index.descendantsOf(idKey("root"))
        assertEquals(
            setOf(root, childA, childB, grandchild),
            descendantsOfRoot.toSet(),
        )
        // Each folder appears exactly once.
        assertEquals(descendantsOfRoot.size, descendantsOfRoot.distinct().size)
        // The node's own item is included.
        assertTrue(root in descendantsOfRoot)

        // A subtree returns only that subtree.
        assertEquals(
            setOf(childA, grandchild),
            index.descendantsOf(idKey("childA")).toSet(),
        )

        // A leaf returns just itself.
        assertEquals(listOf(childB), index.descendantsOf(idKey("childB")))
    }

    @Test
    fun `descendantsOf includes collapsed path direct items`() {
        // Two folders collapse onto path "a/b"; both must surface as descendants
        // of their parent "a".
        val first = pathFolder(path = "a/b", id = "first")
        val second = pathFolder(path = "a/b", id = "second")
        val parent = pathFolder(path = "a", id = "parent")
        val index = index(listOf(parent, first, second))

        val descendantsOfParent = index.descendantsOf(pathKey("a"))
        assertEquals(setOf(parent, first, second), descendantsOfParent.toSet())
        assertEquals(descendantsOfParent.size, descendantsOfParent.distinct().size)
    }
}
