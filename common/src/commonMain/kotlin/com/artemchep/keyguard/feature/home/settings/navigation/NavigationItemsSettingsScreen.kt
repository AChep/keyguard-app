package com.artemchep.keyguard.feature.home.settings.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.vault.component.defaultFlatItemPaddingValues
import com.artemchep.keyguard.feature.home.vault.component.surfaceShape
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.reorder.ReorderableLazyListState
import com.artemchep.keyguard.ui.reorder.move
import com.artemchep.keyguard.ui.reorder.rememberReorderableLazyListState
import com.artemchep.keyguard.ui.reorder.reorderableLazyListDragHandle
import com.artemchep.keyguard.ui.reorder.reorderableLazyListItem
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun NavigationItemsSettingsScreen() {
    when (val state = produceNavigationItemsSettingsState()) {
        is Loadable.Loading -> NavigationItemsSettingsLoadingContent()
        is Loadable.Ok -> NavigationItemsSettingsContent(
            state = state.value,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationItemsSettingsLoadingContent() {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            NavigationItemsSettingsToolbar(
                scrollBehavior = scrollBehavior,
                onReset = null,
            )
        },
    ) {
        skeletonItems()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationItemsSettingsContent(
    state: NavigationItemsSettingsState,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    val listState = rememberLazyListState()
    var localItems by remember {
        mutableStateOf(state.items.toList())
    }
    var dragging by remember {
        mutableStateOf(false)
    }
    val updatedOnReorder by rememberUpdatedState(state.onReorder)
    val reorderState = rememberReorderableLazyListState(
        listState = listState,
        itemKeys = localItems.map { it.key },
        onMove = { fromIndex, toIndex ->
            localItems = localItems.move(fromIndex, toIndex)
        },
        onDragStarted = {
            dragging = true
        },
        onDragStopped = { shouldCommit ->
            dragging = false
            if (shouldCommit) {
                updatedOnReorder(
                    localItems.map { it.ref },
                )
            } else {
                localItems = state.items.toList()
            }
        },
    )
    LaunchedEffect(
        state.items,
    ) {
        if (!dragging) {
            localItems = state.items.toList()
        }
    }

    var addMenuExpanded by remember {
        mutableStateOf(false)
    }
    val addFabState = remember(
        state.availableItems,
    ) {
        if (state.availableItems.isNotEmpty()) {
            FabState(
                onClick = {
                    addMenuExpanded = true
                },
                model = null,
            )
        } else {
            null
        }
    }
    val addFabStateState = rememberUpdatedState(addFabState)
    val moveUpLabel = stringResource(Res.string.list_move_up)
    val moveDownLabel = stringResource(Res.string.list_move_down)
    val hapticFeedback = LocalHapticFeedback.current
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        floatingActionState = addFabStateState,
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                    )
                    NavigationItemsAddMenu(
                        expanded = addMenuExpanded,
                        availableItems = state.availableItems,
                        onDismissRequest = {
                            addMenuExpanded = false
                        },
                    )
                },
                text = {
                    Text(
                        text = stringResource(Res.string.navigation_items_add_item_title),
                    )
                },
            )
        },
        topBar = {
            NavigationItemsSettingsToolbar(
                scrollBehavior = scrollBehavior,
                onReset = state.onReset,
            )
        },
        listState = listState,
    ) {
        itemsIndexed(
            items = localItems,
            key = { _, item -> item.key },
            contentType = { _, _ -> "navigation_item" },
        ) { index, item ->
            val isDraggingItem = reorderState.isDragging(item.key)
            val itemModifier = if (isDraggingItem) {
                Modifier
            } else {
                Modifier.animateItem()
            }
            val shapeState = getShapeState(
                list = localItems,
                index = index,
                predicate = { _, _ -> true },
            )
            val itemShape = surfaceShape(
                shapeState = shapeState,
                expressive = LocalExpressive.current,
            )
            val itemShapePadding = defaultFlatItemPaddingValues()
            CompositionLocalProvider(
                LocalSettingItemShape provides shapeState,
            ) {
                NavigationItemsSettingsItem(
                    modifier = itemModifier
                        .reorderableLazyListItem(
                            reorderState = reorderState,
                            itemKey = item.key,
                            shape = itemShape,
                            shapePadding = itemShapePadding,
                        )
                        .navigationItemsAccessibilityActions(
                            item = item,
                            moveUpLabel = moveUpLabel,
                            moveDownLabel = moveDownLabel,
                        ),
                    dragHandleModifier = Modifier
                        .navigationItemsDragHandle(
                            itemKey = item.key,
                            reorderState = reorderState,
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        ),
                    item = item,
                )
            }
        }

        item("bottom_spacer") {
            Spacer(
                modifier = Modifier
                    .height(Dimens.verticalPadding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationItemsSettingsToolbar(
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onReset: (() -> Unit)?,
) {
    LargeToolbar(
        title = {
            Text(
                text = stringResource(Res.string.settings_navigation_items_header_title),
            )
        },
        navigationIcon = {
            NavigationIcon()
        },
        actions = {
            IconButton(
                enabled = onReset != null,
                onClick = {
                    onReset?.invoke()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = stringResource(Res.string.reset),
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun NavigationItemsSettingsItem(
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    item: NavigationItemsSettingsState.Item,
) {
    val components = LocalSettingPaneComponents.current
    Box(
        modifier = modifier,
    ) {
        components.KgAction(
            icon = item.icon,
            subIcon = item.subIcon,
            title = {
                val style = if (item.ref is NavItemRef.BuiltIn) {
                    MaterialTheme.typography.titleMedium
                } else LocalTextStyle.current
                Text(
                    text = textResource(item.title),
                    style = style,
                )
            },
            trailing = {
                NavigationItemsSettingsItemActions(
                    dragHandleModifier = dragHandleModifier,
                    item = item,
                )
            },
            onClick = item.onVisibilityToggle,
            enabled = true,
        )
    }
}

@Composable
private fun RowScope.NavigationItemsSettingsItemActions(
    dragHandleModifier: Modifier,
    item: NavigationItemsSettingsState.Item,
) {

    if (item.onVisibilityToggle != null) {
        IconButton(
            onClick = item.onVisibilityToggle,
        ) {
            Icon(
                imageVector = if (item.visible) {
                    Icons.Outlined.Visibility
                } else {
                    Icons.Outlined.VisibilityOff
                },
                contentDescription = stringResource(
                    if (item.visible) {
                        Res.string.visible
                    } else {
                        Res.string.hidden
                    },
                ),
            )
        }
    }

    var expanded by remember {
        mutableStateOf(false)
    }
    Box {
        IconButton(
            onClick = {
                expanded = true
            },
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(Res.string.options),
            )
        }

        KeyguardDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            },
        ) {
            val actions = remember(item) {
                buildContextItems {
                    val onMoveUp = item.onMoveUp
                        .takeIf { item.canMoveUp }
                    if (onMoveUp != null) {
                        this += FlatItemAction(
                            icon = Icons.Outlined.ArrowUpward,
                            title = TextHolder.Res(Res.string.list_move_up),
                            onClick = onMoveUp,
                        )
                    }
                    val onMoveDown = item.onMoveDown
                        .takeIf { item.canMoveDown }
                    if (onMoveDown != null) {
                        this += FlatItemAction(
                            icon = Icons.Outlined.ArrowDownward,
                            title = TextHolder.Res(Res.string.list_move_down),
                            onClick = onMoveDown,
                        )
                    }
                    val onRemove = item.onRemove
                        .takeIf { item.canRemove }
                    if (onRemove != null) {
                        this += FlatItemAction(
                            icon = Icons.Outlined.RemoveCircleOutline,
                            title = TextHolder.Res(Res.string.list_remove),
                            onClick = onRemove,
                        )
                    }
                }
            }
            actions.forEach { action ->
                DropdownMenuItemFlat(
                    action = action,
                )
            }
        }
    }

    Box(
        modifier = dragHandleModifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.DragIndicator,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun NavigationItemsAddMenu(
    expanded: Boolean,
    availableItems: List<NavigationItemsSettingsState.AvailableItem>,
    onDismissRequest: () -> Unit,
) {
    KeyguardDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        val actions = remember(availableItems) {
            availableItems
                .map { item ->
                    FlatItemAction(
                        id = item.key,
                        icon = item.icon,
                        title = item.title,
                        text = item.text,
                        onClick = item.onAdd,
                    )
                }
        }
        actions.forEach { action ->
            key(action.id) {
                DropdownMenuItemFlat(
                    action = action,
                )
            }
        }
    }
}

private fun Modifier.navigationItemsAccessibilityActions(
    item: NavigationItemsSettingsState.Item,
    moveUpLabel: String,
    moveDownLabel: String,
): Modifier {
    val actions = buildList {
        item.onMoveUp?.let { onMoveUp ->
            add(
                CustomAccessibilityAction(
                    label = moveUpLabel,
                    action = {
                        onMoveUp()
                        true
                    },
                ),
            )
        }
        item.onMoveDown?.let { onMoveDown ->
            add(
                CustomAccessibilityAction(
                    label = moveDownLabel,
                    action = {
                        onMoveDown()
                        true
                    },
                ),
            )
        }
    }
    if (actions.isEmpty()) {
        return this
    }
    return semantics {
        customActions = actions
    }
}

@Composable
private fun Modifier.navigationItemsDragHandle(
    itemKey: Any,
    reorderState: ReorderableLazyListState,
    onDragStarted: () -> Unit,
): Modifier = reorderableLazyListDragHandle(
    reorderState = reorderState,
    itemKey = itemKey,
    startDragImmediately = true,
    onDragStarted = onDragStarted,
)
