package com.artemchep.keyguard.apple.model

import com.artemchep.keyguard.apple.generator.projectGeneratorHistory
import com.artemchep.keyguard.apple.vault.toFoldersSnapshot
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.generator.history.GeneratorHistoryState
import com.artemchep.keyguard.feature.home.vault.folders.FoldersState
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.Selection
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SelectionSelectAllTest {
    @Test
    fun `folders publish select all while some folders remain unselected`() = runTest {
        val calls = mutableListOf<String>()
        val handlers = LinkedHashMap<String, () -> Unit>()
        val snapshot = folders(selection(onSelectAll = { calls += "all" })).project(handlers)

        handlers.getValue(assertNotNull(snapshot.selectionSelectAllActionId))()
        assertEquals(listOf("all"), calls)
    }

    @Test
    fun `folders omit select all once everything is selected`() = runTest {
        val handlers = LinkedHashMap<String, () -> Unit>()
        val snapshot = folders(selection(onSelectAll = null)).project(handlers)

        assertNull(snapshot.selectionSelectAllActionId)
        assertFalse(SELECTION_SELECT_ALL_ACTION_ID in handlers)
    }

    @Test
    fun `generator history routes select all through the selection actions`() = runTest {
        val calls = mutableListOf<String>()
        val (snapshot, actions) = projectGeneratorHistory(
            state = history(selection(onSelectAll = { calls += "all" })),
            leContext = LeContext(),
        )

        val id = assertNotNull(snapshot.selectionSelectAllActionId)
        actions.selection.getValue(id)()
        assertEquals(listOf("all"), calls)
        assertFalse(id in actions.items || id in actions.screen)
    }

    @Test
    fun `generator history omits select all once everything is selected`() = runTest {
        val (snapshot, actions) = projectGeneratorHistory(
            state = history(selection(onSelectAll = null)),
            leContext = LeContext(),
        )

        assertNull(snapshot.selectionSelectAllActionId)
        assertFalse(SELECTION_SELECT_ALL_ACTION_ID in actions.selection)
    }

    @Test
    fun `no selection publishes no select all`() = runTest {
        val (snapshot, _) = projectGeneratorHistory(state = history(selection = null), leContext = LeContext())

        assertNull(snapshot.selectionSelectAllActionId)
    }

    private fun selection(onSelectAll: (() -> Unit)?) = Selection(
        count = 2,
        actions = persistentListOf(),
        onSelectAll = onSelectAll,
        onClear = {},
    )

    private fun folders(selection: Selection) = FoldersState(
        selection = selection,
        content = Loadable.Ok(FoldersState.Content(items = persistentListOf())),
    )

    private suspend fun FoldersState.project(handlers: LinkedHashMap<String, () -> Unit>) =
        toFoldersSnapshot(
            accountId = null,
            parentId = null,
            leContext = LeContext(),
            handlers = handlers,
        )

    private fun history(selection: Selection?) = GeneratorHistoryState(
        selection = selection,
        options = persistentListOf(),
        items = persistentListOf(),
    )
}
