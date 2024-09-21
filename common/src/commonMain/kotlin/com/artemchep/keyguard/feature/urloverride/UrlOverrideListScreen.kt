@file:OptIn(ExperimentalMaterial3Api::class)

package com.artemchep.keyguard.feature.urloverride

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.flatMap
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.ErrorView
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AvatarBuilder
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.withIndex

@Composable
fun UrlOverrideListScreen() {
    val loadableState = produceUrlOverrideListState(
    )
    EmailRelayListScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EmailRelayListScreen(
    loadableState: Loadable<UrlOverrideListState>,
) {
    val scrollBehavior = ToolbarBehavior.behavior()

    val listRevision =
        loadableState.getOrNull()?.content?.getOrNull()?.getOrNull()?.revision
    val listState = remember {
        LazyListState(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )
    }

    LaunchedEffect(listRevision) {
        // TODO: How do you wait till the layout state start to represent
        //  the actual data?
        val listSize =
            loadableState.getOrNull()?.content?.getOrNull()?.getOrNull()?.items?.size
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .withIndex()
            .filter {
                it.index > 0 || it.value == listSize
            }
            .first()

        listState.scrollToItem(0, 0)
    }

    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.urloverride_list_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    val navigationController by rememberUpdatedState(LocalNavigationController.current)
                    IconButton(
                        onClick = {
                            val intent = NavigationIntent.NavigateToBrowser(
                                url = "https://github.com/AChep/keyguard-app/blob/master/wiki/URL_OVERRIDE.md",
                            )
                            navigationController.queue(intent)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            val selectionOrNull =
                loadableState.getOrNull()?.content?.getOrNull()?.getOrNull()?.selection
            DefaultSelection(
                state = selectionOrNull,
            )
        },
        floatingActionState = run {
            val onClick =
                loadableState.getOrNull()?.content?.getOrNull()?.getOrNull()?.primaryAction
            val state = FabState(
                onClick = onClick,
                model = null,
            )
            rememberUpdatedState(newValue = state)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    IconBox(main = Icons.Outlined.Add)
                },
                text = {
                    Text(
                        text = stringResource(Res.string.add),
                    )
                },
            )
        },
        listState = listState,
    ) {
        val contentState = loadableState
            .flatMap { it.content }
        when (contentState) {
            is Loadable.Loading -> {
                for (i in 1..3) {
                    item("skeleton.$i") {
                        SkeletonItem()
                    }
                }
            }

            is Loadable.Ok -> {
                contentState.value.fold(
                    ifLeft = { e ->
                        item("error") {
                            ErrorView(
                                text = {
                                    Text(text = "Failed to load URL override list!")
                                },
                                exception = e,
                            )
                        }
                    },
                    ifRight = { content ->
                        val items = content.items
                        if (items.isEmpty()) {
                            item("empty") {
                                NoItemsPlaceholder()
                            }
                        }

                        items(
                            items = items,
                            key = { it.key },
                        ) { item ->
                            UrlOverrideItem(
                                modifier = Modifier
                                    .animateItem(),
                                item = item,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NoItemsPlaceholder(
    modifier: Modifier = Modifier,
) {
    EmptyView(
        modifier = modifier,
        text = {
            Text(
                text = stringResource(Res.string.urloverride_empty_label),
            )
        },
    )
}

@Composable
private fun UrlOverrideItem(
    modifier: Modifier,
    item: UrlOverrideListState.Item,
) {
    val selectableState by item.selectableState.collectAsState()
    val backgroundColor = when {
        selectableState.selected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Unspecified
    }
    FlatDropdown(
        modifier = modifier,
        backgroundColor = backgroundColor,
        leading = {
            val accent = rememberSecretAccentColor(
                accentLight = item.accentLight,
                accentDark = item.accentDark,
            )
            AvatarBuilder(
                icon = item.icon,
                accent = accent,
                active = item.active,
                badge = {
                    // Do nothing.
                },
            )
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(item.title)
                },
                text = {
                    Column {
                        Spacer(
                            modifier = Modifier
                                .height(4.dp),
                        )
                        val codeModifier = Modifier
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                modifier = Modifier
                                    .width(14.dp),
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                tint = LocalTextStyle.current.color,
                            )
                            Spacer(
                                modifier = Modifier
                                    .width(8.dp),
                            )
                            Text(
                                modifier = codeModifier,
                                text = item.regex,
                                fontFamily = FontFamily.Monospace,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                            )
                        }
                        Spacer(
                            modifier = Modifier
                                .height(4.dp),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                modifier = Modifier
                                    .width(14.dp),
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = null,
                                tint = LocalTextStyle.current.color,
                            )
                            Spacer(
                                modifier = Modifier
                                    .width(8.dp),
                            )
                            Text(
                                modifier = codeModifier,
                                text = item.command,
                                fontFamily = FontFamily.Monospace,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 6,
                            )
                        }
                    }
                },
            )
        },
        trailing = {
            ExpandedIfNotEmptyForRow(
                selectableState.selected.takeIf { selectableState.selecting },
            ) { selected ->
                Checkbox(
                    modifier = Modifier
                        .padding(start = 16.dp),
                    checked = selected,
                    onCheckedChange = null,
                )
            }
        },
        dropdown = item.dropdown,
        onClick = selectableState.onClick,
        onLongClick = selectableState.onLongClick,
        enabled = true,
    )
}
