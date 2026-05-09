@file:OptIn(ExperimentalMaterial3Api::class)

package com.artemchep.keyguard.feature.home.vault.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.add.AddScreenItems
import com.artemchep.keyguard.feature.add.AddScreenScope
import com.artemchep.keyguard.feature.add.AnyField
import com.artemchep.keyguard.feature.add.ToolbarContent
import com.artemchep.keyguard.feature.add.ToolbarContentItemErrSkeleton
import com.artemchep.keyguard.feature.add.getAnyFieldShapeState
import com.artemchep.keyguard.feature.filepicker.FileDropOverlay
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.filepicker.fileDropTarget
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.button.FavouriteToggleButton
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
fun AddScreen(
    args: AddRoute.Args,
) {
    val loadableState = produceAddScreenState(
        args = args,
    )

    val state = loadableState.getOrNull()
    if (state != null) {
        FilePickerEffect(
            flow = state.sideEffects.filePickerIntentFlow,
        )
    }

    val addScreenScope = remember {
        val addScreenBehavior = args.behavior
        AddScreenScope(
            initialFocusRequested = !addScreenBehavior.autoShowKeyboard,
        )
    }
    // Provide all the items to the shared
    // scope for every item to access.
    run {
        val items = loadableState.getOrNull()?.items.orEmpty()
        addScreenScope.updateItems(items)
    }
    AddScreenContent(
        addScreenScope = addScreenScope,
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddScreenContent(
    addScreenScope: AddScreenScope,
    loadableState: Loadable<AddState>,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    val fileDrag = loadableState.getOrNull()?.fileDrag
    val fileDragState = rememberUpdatedState(fileDrag)
    var fileDragActive by remember(fileDrag) {
        mutableStateOf(false)
    }
    ScaffoldLazyColumn(
        modifier = Modifier
            .fileDropTarget(
                enabled = fileDrag != null,
                onDragActiveChange = { isActive ->
                    fileDragActive = isActive
                },
                onFileDrop = { file ->
                    fileDragState.value?.onFileDrop?.invoke(file)
                },
            )
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    val title = loadableState.getOrNull()?.title
                    if (title != null) {
                        Text(title)
                    } else {
                        SkeletonText(
                            modifier = Modifier
                                .fillMaxWidth(0.4f),
                        )
                    }
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    val fav = loadableState.getOrNull()?.favourite

                    val checked = fav?.checked == true
                    val onChange = fav?.onChange
                    FavouriteToggleButton(
                        modifier = Modifier
                            .then(
                                loadableState.fold(
                                    ifLoading = {
                                        Modifier
                                            .shimmer()
                                    },
                                    ifOk = {
                                        Modifier
                                    },
                                ),
                            ),
                        favorite = checked,
                        onChange = onChange,
                    )

                    val actions = loadableState.getOrNull()?.actions.orEmpty()
                    OptionsButton(
                        actions = actions,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val fabOnClick = loadableState.getOrNull()?.onSave
            val fabState = if (fabOnClick != null) {
                FabState(
                    onClick = fabOnClick,
                    model = null,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Save,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        text = stringResource(Res.string.save),
                    )
                },
            )
        },
        listVerticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        populateItems(
            addScreenScope = addScreenScope,
            loadableState = loadableState,
            fileDragActive = fileDragActive,
        )
    }
}

private fun LazyListScope.populateItems(
    addScreenScope: AddScreenScope,
    loadableState: Loadable<AddState>,
    fileDragActive: Boolean,
) = loadableState.fold(
    ifLoading = {
        populateItemsSkeleton(
            addScreenScope = addScreenScope,
        )
    },
    ifOk = { state ->
        populateItemsContent(
            addScreenScope = addScreenScope,
            state = state,
            fileDragActive = fileDragActive,
        )
    },
)

private fun LazyListScope.populateItemsSkeleton(
    addScreenScope: AddScreenScope,
) {
    item("ownership") {
        AddScreenToolbarSkeletonItem()
    }
    item("ownership.section") {
        Section()
    }
    item("items") {
        Spacer(
            modifier = Modifier
                .height(24.dp),
        )
    }
    with(addScreenScope) {
        AddScreenItems()
    }
}

private fun LazyListScope.populateItemsContent(
    addScreenScope: AddScreenScope,
    state: AddState,
    fileDragActive: Boolean,
) {
    item("ownership") {
        AddScreenToolbarItem(
            state = state,
        )
    }
    item("ownership.section") {
        Section()
    }
    if (state.merge != null) {
        item("merge") {
            AddScreenMergeItem(
                modifier = Modifier,
                state = state.merge,
            )
        }
        item("merge.section") {
            Section()
        }
    }
    item("items") {
        Spacer(
            modifier = Modifier
                .height(24.dp),
        )
    }

    val items = state.items
    val fileDrag = state.fileDrag
    items.forEachIndexed { index, item ->
        if (
            fileDragActive &&
            fileDrag != null &&
            fileDrag.anchorItemId == item.id
        ) {
            item("${fileDrag.anchorItemId}.drop") {
                AddScreenAttachmentFileDropItem(
                    fileDrag = fileDrag,
                )
            }
        }
        item(item.id) {
            with(addScreenScope) {
                val shapeState = getAnyFieldShapeState(
                    list = items,
                    index = index,
                )
                AnyField(
                    modifier = Modifier
                        .animateItem(),
                    item = item,
                    shapeState = shapeState,
                )
            }
        }
    }
}

@Composable
private fun AddScreenAttachmentFileDropItem(
    modifier: Modifier = Modifier,
    fileDrag: AddState.FileDrag,
) {
    FileDropOverlay(
        modifier = modifier
            .padding(horizontal = Dimens.buttonHorizontalPadding)
            .fillMaxWidth(),
        text = fileDrag.text,
    )
}

@Composable
private fun AddScreenToolbarItem(
    modifier: Modifier = Modifier,
    state: AddState,
) {
    ToolbarContent(
        modifier = modifier,
        account = state.ownership.ui.account,
        organization = state.ownership.ui.organization,
        collection = state.ownership.ui.collection,
        folder = state.ownership.ui.folder,
        onClick = state.ownership.ui.onClick,
    )
}

@Composable
private fun AddScreenToolbarSkeletonItem(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 8.dp,
                vertical = 2.dp,
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarContentItemErrSkeleton(
                modifier = Modifier
                    .weight(1.5f),
                fraction = 0.5f,
            )
            Spacer(modifier = Modifier.width(8.dp))
            ToolbarContentItemErrSkeleton(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                fraction = 0.75f,
            )
        }
        ToolbarContentItemErrSkeleton(
            modifier = Modifier
                .padding(end = 8.dp),
            fraction = 0.35f,
        )
    }
}

@Composable
private fun AddScreenMergeItem(
    modifier: Modifier = Modifier,
    state: AddState.Merge,
) = Column(
    modifier = modifier,
) {
    ExpandedIfNotEmpty(
        valueOrNull = state.note,
    ) { note ->
        FlatSimpleNote(
            modifier = Modifier,
            note = note,
        )
    }
    Spacer(
        modifier = Modifier
            .height(8.dp),
    )

    val updatedOnChangeState by rememberUpdatedState(state.onChangePostAction)
    val postActionIconImageVector = remember(state.postAction) {
        state.postAction.iconImageVector()
            .takeIf { it !== Icons.Stub }
    }
    val postActionTitleStringRes = remember(state.postAction) {
        state.postAction.titleStringRes()
    }
    val postActionDropdown = remember {
        sequence {
            yield(null)
            yieldAll(CreateRequest.Merge.PostAction.entries)
        }
            .map { postAction ->
                val titleRes = postAction.titleStringRes()
                val icon = postAction.iconImageVector()
                FlatItemAction(
                    icon = icon,
                    title = TextHolder.Res(titleRes),
                    onClick = {
                        updatedOnChangeState?.invoke(postAction)
                    },
                )
            }
            .toImmutableList()
    }
    FlatDropdownSimpleExpressive(
        dropdown = postActionDropdown,
        leading = postActionIconImageVector
            ?.let { icon ->
                icon<RowScope>(icon)
            },
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(postActionTitleStringRes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
            )
        },
        trailing = {
            DropdownIcon()
        },
    )
}

private fun CreateRequest.Merge.PostAction?.titleStringRes() = when (this) {
    null -> Res.string.additem_merge_keep_origin_ciphers_title
    CreateRequest.Merge.PostAction.TRASH -> Res.string.additem_merge_remove_origin_ciphers_title
    CreateRequest.Merge.PostAction.ARCHIVE -> Res.string.additem_merge_archive_origin_ciphers_title
}

private fun CreateRequest.Merge.PostAction?.iconImageVector() = when (this) {
    null -> Icons.Stub
    CreateRequest.Merge.PostAction.TRASH -> Icons.Outlined.Delete
    CreateRequest.Merge.PostAction.ARCHIVE -> Icons.Outlined.Archive
}
