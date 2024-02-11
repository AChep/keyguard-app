@file:OptIn(ExperimentalMaterial3Api::class)

package com.artemchep.keyguard.feature.home.vault.folders

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.AhLayout
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.animatedNumberText
import com.artemchep.keyguard.ui.icons.OfflineIcon
import com.artemchep.keyguard.ui.skeleton.SkeletonItemPilled
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun FoldersScreen(
    args: FoldersRoute.Args,
) {
    val state = foldersScreenState(
        args = args,
    )
    FoldersScreenContent(
        state = state,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FoldersScreenContent(
    state: FoldersState,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Column {
                        Text(
                            text = stringResource(Res.strings.account),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current
                                .combineAlpha(MediumEmphasisAlpha),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
                        )
                        Text(
                            text = stringResource(Res.strings.folders),
                            style = MaterialTheme.typography.titleMedium,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val fabOnClick = state.onAdd
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
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        text = stringResource(Res.strings.folder_new),
                    )
                },
            )
        },
        bottomBar = {
            DefaultSelection(
                state = state.selection,
            )
        },
    ) {
        when (val contentState = state.content) {
            is Loadable.Loading -> {
                for (i in 1..3) {
                    item("skeleton.$i") {
                        SkeletonItemPilled()
                    }
                }
            }

            is Loadable.Ok -> {
                val items = contentState.value.items
                if (items.isEmpty()) {
                    item("empty") {
                        EmptyView(
                            icon = {
                                Icon(Icons.Outlined.FolderOff, null)
                            },
                            text = {
                                Text(
                                    text = stringResource(Res.strings.folders_empty_label),
                                )
                            },
                        )
                    }
                }

                items(
                    items = items,
                    key = { it.key },
                ) {
                    FoldersScreenItem(
                        modifier = Modifier
                            .animateItemPlacement(),
                        item = it,
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldersScreenItem(
    modifier: Modifier = Modifier,
    item: FoldersState.Content.Item,
) = when (item) {
    is FoldersState.Content.Item.Section -> FoldersScreenSectionItem(modifier, item)
    is FoldersState.Content.Item.Folder -> FoldersScreenFolderItem(modifier, item)
}

@Composable
private fun FoldersScreenSectionItem(
    modifier: Modifier = Modifier,
    item: FoldersState.Content.Item.Section,
) {
    Section(
        modifier = modifier,
        text = item.text,
    )
}

@Composable
private fun FoldersScreenFolderItem(
    modifier: Modifier = Modifier,
    item: FoldersState.Content.Item.Folder,
) {
    val backgroundColor =
        if (item.selected) MaterialTheme.colorScheme.primaryContainer else Color.Unspecified
    FlatDropdown(
        modifier = modifier,
        backgroundColor = backgroundColor,
        dropdown = item.actions,
        leading = {
            val pillElevation = LocalAbsoluteTonalElevation.current + 8.dp
            val pillColor = MaterialTheme.colorScheme
                .surfaceColorAtElevationSemi(elevation = pillElevation)
            AhLayout(
                modifier = modifier,
                backgroundColor = pillColor,
            ) {
                val text = animatedNumberText(item.ciphers)
                Text(text)
            }
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(item.title)
                },
                text = if (item.text != null) {
                    // composable
                    {
                        Text(item.text)
                    }
                } else {
                    null
                },
            )
        },
        trailing = {
            ExpandedIfNotEmptyForRow(
                Unit.takeUnless { item.synced },
                modifier = Modifier
                    .alpha(LocalContentColor.current.alpha),
            ) {
                OfflineIcon(
                    modifier = Modifier
                        .padding(end = 8.dp),
                )
            }
            ExpandedIfNotEmptyForRow(
                item.selected.takeIf { item.selecting },
            ) { selected ->
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                )
            }
        },
        onClick = item.onClick,
        onLongClick = item.onLongClick,
    )
}
