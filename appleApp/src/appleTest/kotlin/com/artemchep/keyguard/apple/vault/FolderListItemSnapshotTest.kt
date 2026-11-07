package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.feature.home.vault.folders.FoldersState
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FolderListItemSnapshotTest {
    @Test
    fun `parent row opens children while direct items remain a separate action`() {
        val calls = mutableListOf<String>()
        val handlers = mutableMapOf<String, () -> Unit>()
        val snapshot = folder(
            hasChildren = true,
            onClick = { calls += "children" },
            onViewItemsClick = { calls += "items" },
        ).toFolderListItemSnapshot(handlers)

        handlers.getValue(assertNotNull(snapshot.navigationActionId))()
        assertEquals(listOf("children"), calls)
        handlers.getValue(assertNotNull(snapshot.itemsActionId))()
        assertEquals(listOf("children", "items"), calls)
    }

    @Test
    fun `leaf folder row opens contents so its first child can be created`() {
        val calls = mutableListOf<String>()
        val handlers = mutableMapOf<String, () -> Unit>()
        val snapshot = folder(
            onClick = { calls += "folder" },
            onViewItemsClick = { calls += "items" },
        ).toFolderListItemSnapshot(handlers)

        handlers.getValue(assertNotNull(snapshot.navigationActionId))()
        assertEquals(listOf("folder"), calls)
        handlers.getValue(assertNotNull(snapshot.itemsActionId))()
        assertEquals(listOf("folder", "items"), calls)
    }

    @Test
    fun `selection toggle remains independent from parent navigation`() {
        val calls = mutableListOf<String>()
        val handlers = mutableMapOf<String, () -> Unit>()
        val snapshot = folder(
            hasChildren = true,
            onClick = { calls += "children" },
            onLongClick = { calls += "select" },
        ).toFolderListItemSnapshot(handlers)

        handlers.getValue(assertNotNull(snapshot.toggleActionId))()
        assertEquals(listOf("select"), calls)
        handlers.getValue(assertNotNull(snapshot.navigationActionId))()
        assertEquals(listOf("select", "children"), calls)
    }

    @Test
    fun `unavailable folder has no navigation or selection handler`() {
        val handlers = mutableMapOf<String, () -> Unit>()
        val snapshot = folder().toFolderListItemSnapshot(handlers)

        assertNull(snapshot.navigationActionId)
        assertNull(snapshot.toggleActionId)
        assertEquals(emptyMap(), handlers)
    }

    private fun folder(
        hasChildren: Boolean = false,
        onClick: (() -> Unit)? = null,
        onViewItemsClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
    ) = FoldersState.Content.Item.Folder(
        key = "folder-key",
        title = "Parent",
        ciphers = 1,
        folders = if (hasChildren) 1 else 0,
        selecting = false,
        selected = false,
        synced = true,
        failed = false,
        hasChildren = hasChildren,
        onViewItemsClick = onViewItemsClick,
        actions = persistentListOf(),
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
