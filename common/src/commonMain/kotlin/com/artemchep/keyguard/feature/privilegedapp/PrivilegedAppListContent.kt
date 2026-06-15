package com.artemchep.keyguard.feature.privilegedapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.flatMap
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.Selection

sealed interface PrivilegedAppListContentState {
    data object Loading : PrivilegedAppListContentState

    data class Error(
        val exception: Throwable,
    ) : PrivilegedAppListContentState

    data class Content(
        val content: PrivilegedAppListState.Content,
    ) : PrivilegedAppListContentState
}

fun Loadable<PrivilegedAppListState>.toPrivilegedAppListContentState(): PrivilegedAppListContentState {
    val contentState = flatMap { it.content }
    return when (contentState) {
        is Loadable.Loading -> PrivilegedAppListContentState.Loading
        is Loadable.Ok -> contentState.value.fold(
            ifLeft = { PrivilegedAppListContentState.Error(it) },
            ifRight = { PrivilegedAppListContentState.Content(it) },
        )
    }
}

data class PrivilegedAppItemRenderers(
    val section: @Composable (Modifier, PrivilegedAppListState.Item.Section) -> Unit,
    val content: @Composable (Modifier, PrivilegedAppListState.Item.Content) -> Unit,
)

@Composable
fun PrivilegedAppItemContent(
    modifier: Modifier = Modifier,
    item: PrivilegedAppListState.Item,
    renderers: PrivilegedAppItemRenderers,
) = when (item) {
    is PrivilegedAppListState.Item.Section -> renderers.section(modifier, item)
    is PrivilegedAppListState.Item.Content -> renderers.content(modifier, item)
}

sealed interface PrivilegedAppSelectionItem {
    val key: String

    data class Label(
        val count: Int,
        override val key: String = "selection.label",
    ) : PrivilegedAppSelectionItem

    data class SelectAll(
        val onClick: () -> Unit,
        override val key: String = "selection.select_all",
    ) : PrivilegedAppSelectionItem

    data class Action(
        val index: Int,
        val action: ContextItem,
    ) : PrivilegedAppSelectionItem {
        override val key: String = "selection.action.$index"
    }

    data class Clear(
        val onClick: () -> Unit,
        override val key: String = "selection.clear",
    ) : PrivilegedAppSelectionItem
}

fun Selection.toPrivilegedAppSelectionItems(): List<PrivilegedAppSelectionItem> = buildList {
    add(
        PrivilegedAppSelectionItem.Label(
            count = count,
        ),
    )
    onSelectAll?.let { onSelectAll ->
        add(
            PrivilegedAppSelectionItem.SelectAll(
                onClick = onSelectAll,
            ),
        )
    }
    actions.forEachIndexed { index, action ->
        add(
            PrivilegedAppSelectionItem.Action(
                index = index,
                action = action,
            ),
        )
    }
    onClear?.let { onClear ->
        add(
            PrivilegedAppSelectionItem.Clear(
                onClick = onClear,
            ),
        )
    }
}
