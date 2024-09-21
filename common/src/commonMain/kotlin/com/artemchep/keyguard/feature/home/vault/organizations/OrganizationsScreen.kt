@file:OptIn(ExperimentalMaterial3Api::class)

package com.artemchep.keyguard.feature.home.vault.organizations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
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
import com.artemchep.keyguard.ui.skeleton.SkeletonItemPilled
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.isDark
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun FoldersScreen(
    args: OrganizationsRoute.Args,
) {
    val state = organizationsScreenState(
        args = args,
    )
    FoldersScreenContent(
        state = state,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FoldersScreenContent(
    state: OrganizationsState,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Column {
                        Text(
                            text = stringResource(Res.string.account),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current
                                .combineAlpha(MediumEmphasisAlpha),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
                        )
                        Text(
                            text = stringResource(Res.string.organizations),
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
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                    )
                },
                text = {
                    Text("New organization")
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
                            text = {
                                Text(
                                    text = stringResource(Res.string.organizations_empty_label),
                                )
                            },
                        )
                    }
                }

                items(
                    items = items,
                    key = { it.key },
                ) {
                    OrganizationsScreenItem(
                        modifier = Modifier
                            .animateItem(),
                        item = it,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrganizationsScreenItem(
    modifier: Modifier = Modifier,
    item: OrganizationsState.Content.Item,
) {
    val backgroundColor =
        if (item.selected) MaterialTheme.colorScheme.primaryContainer else Color.Unspecified
    FlatDropdown(
        modifier = modifier,
        backgroundColor = backgroundColor,
        dropdown = item.actions,
        leading = {
            val pillElevation = LocalAbsoluteTonalElevation.current + 8.dp
            val pillColor = if (MaterialTheme.colorScheme.isDark) {
                item.accentColors.dark
            } else {
                item.accentColors.light
            }
            val contentColor = if (pillColor.luminance() > 0.5f) {
                Color.Black
            } else {
                Color.White
            }.combineAlpha(0.8f).compositeOver(pillColor)
            AhLayout(
                modifier = modifier,
                backgroundColor = pillColor,
                contentColor = contentColor,
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
            )
        },
        trailing = {
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
