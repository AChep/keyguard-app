package com.artemchep.keyguard.feature.home.vault.folders

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class FolderBrowseTreeTest {
    @Test
    fun `path folders with missing ancestors are exposed as real roots`() {
        val folders = listOf(
            folder(id = "1", name = "Work/Clients/Acme"),
        )

        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1"),
            parent = null,
        )
        assertEquals(listOf("Work/Clients/Acme"), root.items.map { it.name })
        assertFalse(root.items.single().hasVisibleChildren)
        assertEquals(setOf("1"), root.items.single().directFolderIds)
        assertEquals(setOf("1"), root.items.single().descendantFolderIds)

        val staleParent = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1"),
            parent = FoldersRoute.Args.Parent.Path(
                accountId = accountId,
                path = "Work",
            ),
        )
        assertEquals(emptyList(), staleParent.items)
    }

    @Test
    fun `path folders use nearest real ancestor when intermediate path is missing`() {
        val folders = listOf(
            folder(id = "1", name = "Work"),
            folder(id = "2", name = "Work/Clients/Acme"),
        )

        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2"),
            parent = null,
        )
        val work = root.items.single()
        assertEquals("Work", work.name)
        assertEquals(setOf("1"), work.directFolderIds)
        assertEquals(setOf("1", "2"), work.descendantFolderIds)
        assertTrue(work.hasVisibleChildren)

        val children = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2"),
            parent = work.anchor,
        )
        val acme = children.items.single()
        assertEquals("Clients/Acme", acme.name)
        assertEquals(setOf("2"), acme.directFolderIds)
        assertFalse(acme.hasVisibleChildren)
    }

    @Test
    fun `path folders expose real ancestors and direct child leaves`() {
        val folders = listOf(
            folder(id = "1", name = "Work"),
            folder(id = "2", name = "Work/Clients"),
        )

        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2"),
            parent = null,
        )
        val work = root.items.single()
        assertEquals("Work", work.name)
        assertEquals(setOf("1"), work.directFolderIds)
        assertEquals(setOf("1", "2"), work.descendantFolderIds)
        assertTrue(work.hasVisibleChildren)

        val children = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2"),
            parent = work.anchor,
        )
        val clients = children.items.single()
        assertEquals("Clients", clients.name)
        assertEquals(setOf("2"), clients.directFolderIds)
        assertFalse(clients.hasVisibleChildren)
    }

    @Test
    fun `parent id folders expose root child and grandchild levels`() {
        val folders = listOf(
            folder(
                id = "1",
                name = "Work",
                hierarchyMode = FolderHierarchyMode.ParentId,
            ),
            folder(
                id = "2",
                name = "Clients",
                parentId = "1",
                hierarchyMode = FolderHierarchyMode.ParentId,
            ),
            folder(
                id = "3",
                name = "Acme",
                parentId = "2",
                hierarchyMode = FolderHierarchyMode.ParentId,
            ),
        )

        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2", "3"),
            parent = null,
        )
        assertEquals(listOf("Work"), root.items.map { it.name })
        assertEquals(setOf("1", "2", "3"), root.items.single().descendantFolderIds)

        val clients = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2", "3"),
            parent = root.items.single().anchor,
        )
        assertEquals(listOf("Clients"), clients.items.map { it.name })

        val acme = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2", "3"),
            parent = clients.items.single().anchor,
        )
        assertEquals(listOf("Acme"), acme.items.map { it.name })
    }

    @Test
    fun `same path folders in one account are grouped into one browse node`() {
        val folders = listOf(
            folder(id = "1", name = "Work"),
            folder(id = "2", name = "Work"),
        )

        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2"),
            parent = null,
        )

        val work = root.items.single()
        assertEquals("Work", work.name)
        assertEquals(setOf("1", "2"), work.directFolderIds)
        assertEquals(setOf("1", "2"), work.descendantFolderIds)
    }

    @Test
    fun `same path folders in different accounts stay separate`() {
        val folders = listOf(
            folder(id = "1", accountId = "account1", name = "Work"),
            folder(id = "2", accountId = "account2", name = "Work"),
        )

        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2"),
            parent = null,
        )

        assertEquals(2, root.items.size)
        assertEquals(setOf("account1", "account2"), root.items.mapTo(mutableSetOf()) { it.anchor.accountId })
        assertEquals(
            setOf("path|account1|Work", "path|account2|Work"),
            root.items.mapTo(mutableSetOf()) { it.key },
        )
    }

    @Test
    fun `filtered descendant keeps ancestors visible`() {
        val folders = listOf(
            folder(id = "1", name = "Work"),
            folder(id = "2", name = "Work/Clients"),
        )

        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("2"),
            parent = null,
        )

        assertEquals(listOf("Work"), root.items.map { it.name })
        assertTrue(root.items.single().hasVisibleChildren)
    }

    @Test
    fun `empty folder mode keeps branch navigation to matching descendants`() {
        val folders = listOf(
            folder(id = "1", name = "Work"),
            folder(id = "2", name = "Work/Clients"),
        )

        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("2"),
            parent = null,
        )
        val children = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("2"),
            parent = root.items.single().anchor,
        )

        assertEquals(listOf("Work"), root.items.map { it.name })
        assertEquals(listOf("Clients"), children.items.map { it.name })
    }

    @Test
    fun `path prefix rename cascades through descendants`() {
        val folders = listOf(
            folder(id = "1", name = "Work"),
            folder(id = "2", name = "Work/Clients"),
            folder(id = "3", name = "Work/Clients/Acme"),
        )
        val node = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = folders.mapTo(mutableSetOf()) { it.id },
            parent = null,
        ).items.single()

        val renameMap = createFolderRenameMap(
            nodes = listOf(node),
            namesByNodeKey = mapOf(node.key to "Business"),
        )

        assertEquals(
            mapOf(
                "1" to "Business",
                "2" to "Business/Clients",
                "3" to "Business/Clients/Acme",
            ),
            renameMap,
        )
    }

    @Test
    fun `path rename uses resolved parent when intermediate ancestor is missing`() {
        val folders = listOf(
            folder(id = "1", name = "Work"),
            folder(id = "2", name = "Work/Clients/Acme"),
            folder(id = "3", name = "Work/Clients/Acme/Prod"),
        )
        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = folders.mapTo(mutableSetOf()) { it.id },
            parent = null,
        )
        val node = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = folders.mapTo(mutableSetOf()) { it.id },
            parent = root.items.single().anchor,
        ).items.single()

        val renameMap = createFolderRenameMap(
            nodes = listOf(node),
            namesByNodeKey = mapOf(node.key to "Partners/Acme"),
        )

        assertEquals(
            mapOf(
                "2" to "Work/Partners/Acme",
                "3" to "Work/Partners/Acme/Prod",
            ),
            renameMap,
        )
    }

    @Test
    fun `parent id cycle is broken and every node stays reachable from the root`() {
        val folders = listOf(
            folder(
                id = "1",
                name = "Alpha",
                parentId = "2",
                hierarchyMode = FolderHierarchyMode.ParentId,
            ),
            folder(
                id = "2",
                name = "Beta",
                parentId = "1",
                hierarchyMode = FolderHierarchyMode.ParentId,
            ),
        )

        // A direct `A -> B -> A` cycle. The index must re-root both folders so
        // the tree build terminates and exposes them, rather than hanging while
        // walking the loop.
        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2"),
            parent = null,
        )

        assertEquals(setOf("Alpha", "Beta"), root.items.mapTo(mutableSetOf()) { it.name })
        // Both nodes were re-rooted, so neither is a descendant of the other.
        val alpha = root.items.single { it.name == "Alpha" }
        val beta = root.items.single { it.name == "Beta" }
        assertEquals(setOf("1"), alpha.descendantFolderIds)
        assertEquals(setOf("2"), beta.descendantFolderIds)
        assertFalse(alpha.hasVisibleChildren)
        assertFalse(beta.hasVisibleChildren)
    }

    @Test
    fun `parent id folder with a dangling parent becomes a real root`() {
        val folders = listOf(
            folder(
                id = "1",
                name = "Orphan",
                parentId = "missing",
                hierarchyMode = FolderHierarchyMode.ParentId,
            ),
        )

        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1"),
            parent = null,
        )

        val orphan = root.items.single()
        assertEquals("Orphan", orphan.name)
        assertEquals(setOf("1"), orphan.directFolderIds)
        assertEquals(setOf("1"), orphan.descendantFolderIds)
        assertFalse(orphan.hasVisibleChildren)
    }

    @Test
    fun `parent id folders nest deeper than three real levels`() {
        val folders = listOf(
            folder(id = "1", name = "L1", hierarchyMode = FolderHierarchyMode.ParentId),
            folder(id = "2", name = "L2", parentId = "1", hierarchyMode = FolderHierarchyMode.ParentId),
            folder(id = "3", name = "L3", parentId = "2", hierarchyMode = FolderHierarchyMode.ParentId),
            folder(id = "4", name = "L4", parentId = "3", hierarchyMode = FolderHierarchyMode.ParentId),
            folder(id = "5", name = "L5", parentId = "4", hierarchyMode = FolderHierarchyMode.ParentId),
        )
        val visibleFolderIds = folders.mapTo(mutableSetOf()) { it.id }

        var node = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = visibleFolderIds,
            parent = null,
        ).items.single()
        assertEquals("L1", node.name)
        assertEquals(setOf("1", "2", "3", "4", "5"), node.descendantFolderIds)

        // Drill all the way down to the fifth level, asserting each level is
        // reachable and exposes exactly its single child.
        val expectedNames = listOf("L2", "L3", "L4", "L5")
        expectedNames.forEach { expectedName ->
            val children = buildFolderBrowseTree(
                folders = folders,
                visibleFolderIds = visibleFolderIds,
                parent = node.anchor,
            )
            node = children.items.single()
            assertEquals(expectedName, node.name)
        }
        assertFalse(node.hasVisibleChildren)
        assertEquals(setOf("5"), node.descendantFolderIds)
    }

    @Test
    fun `delimiter in name collapses into one node without a real intermediate folder`() {
        val folders = listOf(
            folder(id = "1", name = "A/B/C"),
        )

        // With no real `A` or `A/B` folder, the slash-laden name must surface as
        // a single root node rather than synthesising intermediate folders.
        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1"),
            parent = null,
        )
        val node = root.items.single()
        assertEquals("A/B/C", node.name)
        assertEquals(setOf("1"), node.directFolderIds)
        assertFalse(node.hasVisibleChildren)
    }

    @Test
    fun `real intermediate folders nest where a bare delimiter name would not`() {
        val folders = listOf(
            folder(id = "1", name = "A"),
            folder(id = "2", name = "A/B"),
            folder(id = "3", name = "A/B/C"),
        )

        // Same leaf path as the collapsed case above, but with real `A` and
        // `A/B` folders the tree must expose three distinct nesting levels.
        val visibleFolderIds = folders.mapTo(mutableSetOf()) { it.id }
        val root = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = visibleFolderIds,
            parent = null,
        )
        val a = root.items.single()
        assertEquals("A", a.name)
        assertTrue(a.hasVisibleChildren)
        assertEquals(setOf("1", "2", "3"), a.descendantFolderIds)

        val b = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = visibleFolderIds,
            parent = a.anchor,
        ).items.single()
        assertEquals("B", b.name)
        assertTrue(b.hasVisibleChildren)

        val c = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = visibleFolderIds,
            parent = b.anchor,
        ).items.single()
        assertEquals("C", c.name)
        assertFalse(c.hasVisibleChildren)
    }

    @Test
    fun `same name parent id siblings under one parent stay distinct id keyed nodes`() {
        val folders = listOf(
            folder(id = "1", name = "Parent", hierarchyMode = FolderHierarchyMode.ParentId),
            folder(id = "2", name = "Child", parentId = "1", hierarchyMode = FolderHierarchyMode.ParentId),
            folder(id = "3", name = "Child", parentId = "1", hierarchyMode = FolderHierarchyMode.ParentId),
        )
        val visibleFolderIds = folders.mapTo(mutableSetOf()) { it.id }

        val parent = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = visibleFolderIds,
            parent = null,
        ).items.single()
        assertEquals("Parent", parent.name)

        val children = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = visibleFolderIds,
            parent = parent.anchor,
        )

        // Two same-named siblings must NOT collapse: ParentId mode keys by folder
        // id, so each child stays a separate node with its own id-keyed identity.
        assertEquals(listOf("Child", "Child"), children.items.map { it.name })
        assertEquals(
            setOf("id|$accountId|2", "id|$accountId|3"),
            children.items.mapTo(mutableSetOf()) { it.key },
        )
        assertEquals(
            setOf(setOf("2"), setOf("3")),
            children.items.mapTo(mutableSetOf()) { it.directFolderIds },
        )
    }

    @Test
    fun `visible child folder count reflects visible direct children not collapsed folders`() {
        val folders = listOf(
            folder(id = "1", name = "Work"),
            folder(id = "2", name = "Work/Clients"),
            folder(id = "3", name = "Work/Partners"),
        )
        val visibleFolderIds = folders.mapTo(mutableSetOf()) { it.id }

        val work = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = visibleFolderIds,
            parent = null,
        ).items.single()

        // The badge counts the visible direct child nodes (Clients, Partners),
        // not the single folder collapsed into this node.
        assertEquals(1, work.directFolderIds.size)
        assertEquals(2, work.visibleChildFolderCount)

        // A hidden child must not be counted.
        val workPartial = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = setOf("1", "2"),
            parent = null,
        ).items.single()
        assertEquals(1, workPartial.visibleChildFolderCount)

        // A leaf with no children has a zero count.
        val clients = buildFolderBrowseTree(
            folders = folders,
            visibleFolderIds = visibleFolderIds,
            parent = work.anchor,
        ).items.single { it.name == "Clients" }
        assertEquals(0, clients.visibleChildFolderCount)
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
