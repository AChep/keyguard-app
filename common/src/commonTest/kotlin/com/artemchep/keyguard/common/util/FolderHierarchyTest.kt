package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.common.model.FolderHierarchyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FolderHierarchyTest {
    private data class TestFolder(
        val id: String,
        val name: String,
        val parentId: String? = null,
    )

    private fun pathHierarchy(
        allFolders: Collection<TestFolder>,
        folder: TestFolder,
    ): FolderHierarchy<TestFolder> = createFolderHierarchy(
        lens = TestFolder::name,
        allFolders = allFolders,
        folder = folder,
        id = TestFolder::id,
        parentId = TestFolder::parentId,
        hierarchyMode = FolderHierarchyMode.Path,
    )

    private fun parentIdHierarchy(
        allFolders: Collection<TestFolder>,
        folder: TestFolder,
    ): FolderHierarchy<TestFolder> = createFolderHierarchy(
        lens = TestFolder::name,
        allFolders = allFolders,
        folder = folder,
        id = TestFolder::id,
        parentId = TestFolder::parentId,
        hierarchyMode = FolderHierarchyMode.ParentId,
    )

    // region Path mode

    @Test
    fun `Path mode slices node name by the path delimiter`() {
        val work = TestFolder(id = "1", name = "Work")
        val projects = TestFolder(id = "2", name = "Work/Projects")
        val allFolders = listOf(work, projects)

        val hierarchy = pathHierarchy(
            allFolders = allFolders,
            folder = projects,
        )

        assertEquals(
            expected = listOf("Work", "Projects"),
            actual = hierarchy.nodes.map { it.name },
        )
        assertEquals(
            expected = listOf(work, projects),
            actual = hierarchy.nodes.map { it.folder },
        )
        assertSame(
            expected = projects,
            actual = hierarchy.folder,
        )
    }

    @Test
    fun `Path mode keeps the root node full name`() {
        val work = TestFolder(id = "1", name = "Work")
        val allFolders = listOf(work)

        val hierarchy = pathHierarchy(
            allFolders = allFolders,
            folder = work,
        )

        assertEquals(
            expected = listOf("Work"),
            actual = hierarchy.nodes.map { it.name },
        )
    }

    @Test
    fun `Path mode resolves the nearest existing ancestor when an intermediate level is missing`() {
        // "Work/Projects" intentionally absent from the collection.
        val work = TestFolder(id = "1", name = "Work")
        val alpha = TestFolder(id = "2", name = "Work/Projects/Alpha")
        val allFolders = listOf(work, alpha)

        val hierarchy = pathHierarchy(
            allFolders = allFolders,
            folder = alpha,
        )

        assertEquals(
            expected = listOf("Work", "Projects/Alpha"),
            actual = hierarchy.nodes.map { it.name },
        )
        assertEquals(
            expected = listOf(work, alpha),
            actual = hierarchy.nodes.map { it.folder },
        )
    }

    @Test
    fun `Path mode builds a full multi level chain`() {
        val work = TestFolder(id = "1", name = "Work")
        val projects = TestFolder(id = "2", name = "Work/Projects")
        val alpha = TestFolder(id = "3", name = "Work/Projects/Alpha")
        val allFolders = listOf(work, projects, alpha)

        val hierarchy = pathHierarchy(
            allFolders = allFolders,
            folder = alpha,
        )

        assertEquals(
            expected = listOf("Work", "Projects", "Alpha"),
            actual = hierarchy.nodes.map { it.name },
        )
        assertEquals(
            expected = listOf(work, projects, alpha),
            actual = hierarchy.nodes.map { it.folder },
        )
    }

    @Test
    fun `Path mode roots a folder that has no path delimiter`() {
        val inbox = TestFolder(id = "1", name = "Inbox")
        val other = TestFolder(id = "2", name = "Archive")
        val allFolders = listOf(inbox, other)

        val hierarchy = pathHierarchy(
            allFolders = allFolders,
            folder = inbox,
        )

        assertEquals(
            expected = listOf("Inbox"),
            actual = hierarchy.nodes.map { it.name },
        )
    }

    // endregion

    // region ParentId mode

    @Test
    fun `ParentId mode follows a normal parent chain`() {
        val root = TestFolder(id = "1", name = "Root", parentId = null)
        val mid = TestFolder(id = "2", name = "Mid", parentId = "1")
        val leaf = TestFolder(id = "3", name = "Leaf", parentId = "2")
        val allFolders = listOf(root, mid, leaf)

        val hierarchy = parentIdHierarchy(
            allFolders = allFolders,
            folder = leaf,
        )

        assertEquals(
            expected = listOf("Root", "Mid", "Leaf"),
            actual = hierarchy.nodes.map { it.name },
        )
        assertEquals(
            expected = listOf(root, mid, leaf),
            actual = hierarchy.nodes.map { it.folder },
        )
    }

    @Test
    fun `ParentId mode re-roots a folder with a dangling parent`() {
        // parentId points at an id that is absent from the collection.
        val orphan = TestFolder(id = "2", name = "Orphan", parentId = "missing")
        val allFolders = listOf(orphan)

        val hierarchy = parentIdHierarchy(
            allFolders = allFolders,
            folder = orphan,
        )

        assertEquals(
            expected = listOf("Orphan"),
            actual = hierarchy.nodes.map { it.name },
        )
    }

    @Test
    fun `ParentId mode terminates on a two node cycle`() {
        // A <-> B mutual parents.
        val a = TestFolder(id = "A", name = "A", parentId = "B")
        val b = TestFolder(id = "B", name = "B", parentId = "A")
        val allFolders = listOf(a, b)

        val hierarchy = parentIdHierarchy(
            allFolders = allFolders,
            folder = a,
        )

        // Walk from A visits A then B, then would revisit A and stops.
        assertEquals(
            expected = listOf("B", "A"),
            actual = hierarchy.nodes.map { it.name },
        )
        assertSame(
            expected = a,
            actual = hierarchy.folder,
        )
        // No duplicates: the cycle guard visits each id at most once.
        assertEquals(
            expected = hierarchy.nodes.size,
            actual = hierarchy.nodes.map { it.folder.id }.toSet().size,
        )
    }

    @Test
    fun `ParentId mode terminates on a self referential parent`() {
        val node = TestFolder(id = "1", name = "Self", parentId = "1")
        val allFolders = listOf(node)

        val hierarchy = parentIdHierarchy(
            allFolders = allFolders,
            folder = node,
        )

        assertEquals(
            expected = listOf("Self"),
            actual = hierarchy.nodes.map { it.name },
        )
        assertSame(
            expected = node,
            actual = hierarchy.folder,
        )
    }

    @Test
    fun `ParentId mode keeps full names without slicing`() {
        val root = TestFolder(id = "1", name = "Work/Projects", parentId = null)
        val leaf = TestFolder(id = "2", name = "Alpha", parentId = "1")
        val allFolders = listOf(root, leaf)

        val hierarchy = parentIdHierarchy(
            allFolders = allFolders,
            folder = leaf,
        )

        // ParentId mode does not strip path prefixes from the name.
        assertEquals(
            expected = listOf("Work/Projects", "Alpha"),
            actual = hierarchy.nodes.map { it.name },
        )
    }

    // endregion

    // region isPathPrefixOf

    @Test
    fun `isPathPrefixOf treats a path as a prefix of itself`() {
        assertTrue(
            actual = isPathPrefixOf(prefix = "Work", path = "Work"),
        )
    }

    @Test
    fun `isPathPrefixOf matches whole path segments`() {
        assertTrue(
            actual = isPathPrefixOf(prefix = "Work", path = "Work/X"),
        )
        assertTrue(
            actual = isPathPrefixOf(prefix = "Work/X", path = "Work/X/Y"),
        )
    }

    @Test
    fun `isPathPrefixOf does not match a partial segment`() {
        assertFalse(
            actual = isPathPrefixOf(prefix = "Work", path = "Workshop"),
        )
        assertFalse(
            actual = isPathPrefixOf(prefix = "Work", path = "Workshop/Tools"),
        )
    }

    @Test
    fun `isPathPrefixOf rejects an unrelated path`() {
        assertFalse(
            actual = isPathPrefixOf(prefix = "Work", path = "Home/Work"),
        )
    }

    // endregion

    // region replacePathPrefix

    @Test
    fun `replacePathPrefix rewrites an exact match`() {
        assertEquals(
            expected = "Personal",
            actual = replacePathPrefix(
                path = "Work",
                oldPrefix = "Work",
                newPrefix = "Personal",
            ),
        )
    }

    @Test
    fun `replacePathPrefix rewrites a segment boundary match`() {
        assertEquals(
            expected = "Personal/Projects/Alpha",
            actual = replacePathPrefix(
                path = "Work/Projects/Alpha",
                oldPrefix = "Work",
                newPrefix = "Personal",
            ),
        )
    }

    @Test
    fun `replacePathPrefix returns null on a partial segment match`() {
        assertNull(
            replacePathPrefix(
                path = "Workshop/Tools",
                oldPrefix = "Work",
                newPrefix = "Personal",
            ),
        )
    }

    @Test
    fun `replacePathPrefix returns null on an unrelated path`() {
        assertNull(
            replacePathPrefix(
                path = "Home/Work",
                oldPrefix = "Work",
                newPrefix = "Personal",
            ),
        )
    }

    // endregion
}
