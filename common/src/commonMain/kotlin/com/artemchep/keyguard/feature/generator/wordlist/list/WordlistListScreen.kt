package com.artemchep.keyguard.feature.generator.wordlist.list

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.flatMap
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.ErrorView
import com.artemchep.keyguard.feature.generator.wordlist.view.WordlistViewRoute
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.AvatarBuilder
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.DropdownScopeImpl
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.withIndex

@Composable
fun WordlistListScreen(
) {
    val loadableState = produceWordlistListState(
    )
    WordlistListScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WordlistListScreen(
    loadableState: Loadable<WordlistListState>,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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

    val primaryActionsDropdownVisibleState = remember {
        mutableStateOf(false)
    }
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.strings.wordlist_list_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    val navigationController by rememberUpdatedState(LocalNavigationController.current)
                    IconButton(
                        onClick = {
                            val intent = NavigationIntent.NavigateToBrowser(
                                url = "https://github.com/AChep/keyguard-app/blob/master/wiki/WORDLISTS.md",
                            )
                            navigationController.queue(intent)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.HelpOutline,
                            contentDescription = null,
                        )
                    }
                },
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
            val actions = loadableState.getOrNull()?.content?.getOrNull()?.getOrNull()?.primaryActions.orEmpty()
            val onClick = if (actions.isNotEmpty()) {
                // lambda
                {
                    primaryActionsDropdownVisibleState.value = true
                }
            } else {
                null
            }
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

                    // Inject the dropdown popup to the bottom of the
                    // content.
                    val onDismissRequest = remember(primaryActionsDropdownVisibleState) {
                        // lambda
                        {
                            primaryActionsDropdownVisibleState.value = false
                        }
                    }
                    DropdownMenu(
                        modifier = Modifier
                            .widthIn(min = DropdownMinWidth),
                        expanded = primaryActionsDropdownVisibleState.value,
                        onDismissRequest = onDismissRequest,
                    ) {
                        val actions = loadableState.getOrNull()?.content?.getOrNull()?.getOrNull()?.primaryActions.orEmpty()
                        val scope = DropdownScopeImpl(this, onDismissRequest = onDismissRequest)
                        with(scope) {
                            actions.forEachIndexed { index, action ->
                                DropdownMenuItemFlat(
                                    action = action,
                                )
                            }
                        }
                    }
                },
                text = {
                    Text(
                        text = stringResource(Res.strings.add),
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
                                    Text(text = "Failed to load wordlist list!")
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
                            WordlistItem(
                                modifier = Modifier
                                    .animateItemPlacement(),
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
                text = stringResource(Res.strings.wordlist_empty_label),
            )
        },
    )
}

@Composable
private fun WordlistItem(
    modifier: Modifier,
    item: WordlistListState.Item,
) {
    val selectableState by item.selectableState.collectAsState()

    val onClick = selectableState.onClick
    // fallback to default
        ?: item.onClick
            .takeIf { selectableState.can }
    val onLongClick = selectableState.onLongClick

    val backgroundColor = when {
        selectableState.selected -> MaterialTheme.colorScheme.primaryContainer
        else -> run {
            if (LocalHasDetailPane.current) {
                val nextEntry = navigationNextEntryOrNull()
                val nextRoute = nextEntry?.route as? WordlistViewRoute

                MaterialTheme.colorScheme.selectedContainer
                    .takeIf { LocalHasDetailPane.current }
                    ?: Color.Unspecified
                val selected = nextRoute?.args?.wordlistId == item.wordlistId
                if (selected) {
                    return@run MaterialTheme.colorScheme.selectedContainer
                }
            }

            Color.Unspecified
        }
    }
    FlatItemLayout(
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
                active = true,
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
                                imageVector = Icons.Outlined.Book,
                                contentDescription = null,
                                tint = LocalTextStyle.current.color,
                            )
                            Spacer(
                                modifier = Modifier
                                    .width(8.dp),
                            )
                            Text(
                                modifier = codeModifier,
                                text = item.counter,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                            )
                        }
                    }
                },
            )
        },
        trailing = {
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )

            val checkbox = when {
                selectableState.selecting -> true
                else -> false
            }
            Crossfade(
                modifier = Modifier
                    .size(
                        width = 36.dp,
                        height = 36.dp,
                    ),
                targetState = checkbox,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (it) {
                        Checkbox(
                            checked = selectableState.selected,
                            onCheckedChange = null,
                        )
                    } else {
                        ChevronIcon()
                    }
                }
            }
        },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
