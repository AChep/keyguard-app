package com.artemchep.keyguard.feature.send.list

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.pullrefresh.PullRefreshState
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.expiredFlow
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.home.vault.component.AddAccountView
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayout2
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.LocalNavigationRouter
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.search.filter.FilterButton
import com.artemchep.keyguard.feature.search.filter.FilterScreen
import com.artemchep.keyguard.feature.search.sort.SortButton
import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.feature.send.SendListState
import com.artemchep.keyguard.feature.send.SendRoute
import com.artemchep.keyguard.feature.send.sendListScreenState
import com.artemchep.keyguard.feature.send.view.SendViewRoute
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.feature.twopane.TwoPaneScreen
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AvatarBadgeIcon
import com.artemchep.keyguard.ui.AvatarBuilder
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.Compose
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.DropdownScopeImpl
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardNote
import com.artemchep.keyguard.ui.icons.KeyguardView
import com.artemchep.keyguard.ui.pulltosearch.PullToSearch
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.toolbar.CustomToolbar
import com.artemchep.keyguard.ui.toolbar.content.CustomSearchbarContent
import org.jetbrains.compose.resources.stringResource

@Composable
fun SendListScreen(
    args: SendRoute.Args,
) {
    val state = sendListScreenState(
        args = args,
        highlightBackgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
        highlightContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        mode = LocalAppMode.current,
    )
    val old = remember {
        mutableStateOf<SendListState?>(null)
    }
    SideEffect {
        old.value = state
    }

    val wtf = (state.content as? SendListState.Content.Items)?.onSelected
    Compose {
        val screenId = LocalNavigationEntry.current.id
        val screenStack = LocalNavigationRouter.current.value
        val childBackStackFlow = remember(
            screenId,
            screenStack,
        ) {
            snapshotFlow {
                val backStack = screenStack
                    .indexOfFirst { it.id == screenId }
                    // take the next screen
                    .inc()
                    // check if in range
                    .takeIf { it in 1 until screenStack.size }
                    ?.let { index ->
                        screenStack.subList(
                            fromIndex = index,
                            toIndex = screenStack.size,
                        )
                    }
                    .orEmpty()
                backStack
            }
        }
        LaunchedEffect(wtf, childBackStackFlow) {
            childBackStackFlow.collect { backStack ->
                val firstRoute = backStack.firstOrNull()?.route as? SendViewRoute?
                wtf?.invoke(firstRoute?.sendId)
            }
        }
    }

    val xd by rememberUpdatedState(LocalNavigationEntry.current.id)
    val controller by rememberUpdatedState(LocalNavigationController.current)
    CollectedEffect(state.sideEffects.showBiometricPromptFlow) {
        val route = SendViewRoute(
            sendId = it.id,
            accountId = it.accountId,
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(xd),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        controller.queue(intent)
    }

    val focusRequester = remember {
        FocusRequester2()
    }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = false,
        onRefresh = {
            focusRequester.requestFocus()
        },
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    TwoPaneScreen(
        header = { modifier ->
            CustomSearchbarContent(
                modifier = modifier,
                searchFieldModifier = Modifier
                    .focusRequester2(focusRequester),
                searchFieldModel = state.query,
                searchFieldPlaceholder = stringResource(Res.string.send_main_search_placeholder),
                title = args.appBar?.title
                    ?: stringResource(Res.string.send_main_header_title),
                subtitle = args.appBar?.subtitle,
                icon = {
                    NavigationIcon()
                },
                actions = {
                    SendListSortButton(
                        state = state,
                    )
                    OptionsButton(
                        actions = state.actions,
                    )
                },
            )
        },
        detail = { modifier ->
            SendListFilterScreen(
                modifier = modifier,
                state = state,
            )
        },
    ) { modifier, tabletUi ->
        SendScreenContent(
            modifier = modifier,
            state = state,
            tabletUi = tabletUi,
            focusRequester = focusRequester,
            title = args.appBar?.title,
            subtitle = args.appBar?.subtitle,
            pullRefreshState = pullRefreshState,
            scrollBehavior = scrollBehavior,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendScreenContent(
    modifier: Modifier = Modifier,
    state: SendListState,
    tabletUi: Boolean,
    focusRequester: FocusRequester2,
    title: String?,
    subtitle: String?,
    pullRefreshState: PullRefreshState,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val dp = remember {
        mutableStateOf(false)
    }
    if (state.primaryActions.isEmpty()) {
        dp.value = false
    }
    ScaffoldLazyColumn(
        modifier = modifier
            .pullRefresh(pullRefreshState)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            if (tabletUi) {
                return@ScaffoldLazyColumn
            }

            CustomToolbar(
                scrollBehavior = scrollBehavior,
            ) {
                CustomSearchbarContent(
                    modifier = Modifier,
                    searchFieldModifier = Modifier
                        .focusRequester2(focusRequester),
                    searchFieldModel = state.query,
                    searchFieldPlaceholder = stringResource(Res.string.send_main_search_placeholder),
                    title = title
                        ?: stringResource(Res.string.send_main_header_title),
                    subtitle = subtitle,
                    icon = {
                        NavigationIcon()
                    },
                    actions = {
                        SendListFilterButton(
                            state = state,
                        )
                        SendListSortButton(
                            state = state,
                        )
                        OptionsButton(
                            actions = state.actions,
                        )
                    },
                )
            }
        },
        floatingActionState = run {
            val fabVisible = state.primaryActions.isNotEmpty()
            val fabState = if (fabVisible) {
                // If there's only one primary action, then there's no
                // need to show the dropdown.
                val onClick = run {
                    val action =
                        state.primaryActions.firstNotNullOfOrNull { it as? FlatItemAction }
                    action?.onClick
                        ?.takeIf {
                            val count = state.primaryActions
                                .count { it is FlatItemAction }
                            count == 1
                        }
                } ?:
                // lambda
                {
                    dp.value = true
                }
                FabState(
                    onClick = onClick,
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
                    IconBox(main = Icons.Outlined.Add)

                    // Inject the dropdown popup to the bottom of the
                    // content.
                    val onDismissRequest = remember(dp) {
                        // lambda
                        {
                            dp.value = false
                        }
                    }
                    DropdownMenu(
                        modifier = Modifier
                            .widthIn(min = DropdownMinWidth),
                        expanded = dp.value,
                        onDismissRequest = onDismissRequest,
                    ) {
                        val scope = DropdownScopeImpl(this, onDismissRequest = onDismissRequest)
                        with(scope) {
                            state.primaryActions.forEachIndexed { index, action ->
                                DropdownMenuItemFlat(
                                    action = action,
                                )
                            }
                        }
                    }
                },
                text = {
                    Text(
                        text = stringResource(Res.string.send_main_new_item_button),
                    )
                },
            )
        },
        pullRefreshState = pullRefreshState,
        bottomBar = {
            val selectionOrNull = (state.content as? SendListState.Content.Items)?.selection
            DefaultSelection(
                state = selectionOrNull,
            )
        },
        overlay = {
            PullToSearch(
                modifier = Modifier
                    .padding(contentPadding.value),
                pullRefreshState = pullRefreshState,
            )
        },
    ) {
        when (state.content) {
            is SendListState.Content.Skeleton -> {
                // Show a bunch of skeleton items, so it makes an impression of a
                // fully loaded screen.
                for (i in 0..3) {
                    item(i) {
                        SkeletonItem(
                            avatar = true,
                        )
                    }
                }
            }

            else -> {
                if (state.content is SendListState.Content.AddAccount) {
                    item("header.add_account") {
                        AddAccountView(
                            onClick = state.content.onAddAccount,
                        )
                    }
                }
                val list = (state.content as? SendListState.Content.Items)?.list.orEmpty()
                if (list.isEmpty()) {
                    item("header.empty") {
                        NoItemsPlaceholder()
                    }
                }
                items(
                    items = list,
                    key = { model -> model.id },
                ) { model ->
                    VaultSendItemText(
                        modifier = Modifier
                            .animateItemPlacement(),
                        item = model,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoItemsPlaceholder(
    modifier: Modifier = Modifier,
) {
    EmptySearchView(
        modifier = modifier,
    )
}

@Composable
private fun SendListFilterScreen(
    modifier: Modifier = Modifier,
    state: SendListState,
) {
    val count = (state.content as? SendListState.Content.Items)?.count
    val filters = state.filters
    val clearFilters = state.clearFilters
    FilterScreen(
        modifier = modifier,
        count = count,
        items = filters,
        onClear = clearFilters,
    )
}

@Composable
private fun SendListFilterButton(
    modifier: Modifier = Modifier,
    state: SendListState,
) {
    val count = (state.content as? SendListState.Content.Items)?.count
    val filters = state.filters
    val clearFilters = state.clearFilters
    val saveFilters = state.saveFilters
    FilterButton(
        modifier = modifier,
        count = count,
        items = filters,
        onClear = clearFilters,
        onSave = saveFilters,
    )
}

@Composable
private fun SendListSortButton(
    modifier: Modifier = Modifier,
    state: SendListState,
) {
    val filters = state.sort
    val clearFilters = state.clearSort
    SortButton(
        modifier = modifier,
        items = filters,
        onClear = clearFilters,
    )
}

@Composable
fun VaultSendItemText(
    modifier: Modifier = Modifier,
    item: SendItem,
) = when (item) {
    is SendItem.Item -> VaultSendItemText(
        modifier = modifier,
        item = item,
    )

    is SendItem.Section -> {
        Section(
            modifier = modifier,
            text = item.text,
            caps = item.caps,
        )
    }
}

@Composable
fun VaultSendItemText(
    modifier: Modifier = Modifier,
    item: SendItem.Item,
) {
    val localState by item.localStateFlow.collectAsState()
    val expiredState = remember(item.source) {
        item.source.expiredFlow
    }.collectAsState()

    val onClick = localState.selectableItemState.onClick
    // fallback to default
        ?: when (item.action) {
            is SendItem.Item.Action.Go -> item.action.onClick
        }.takeIf { localState.selectableItemState.can }
    val onLongClick = localState.selectableItemState.onLongClick

    val backgroundColor = when {
        localState.selectableItemState.selected -> MaterialTheme.colorScheme.primaryContainer
        localState.openedState.isOpened ->
            MaterialTheme.colorScheme.selectedContainer
                .takeIf { LocalHasDetailPane.current }
                ?: Color.Unspecified

        else -> Color.Unspecified
    }
    FlatItemLayout2(
        modifier = modifier,
        backgroundColor = backgroundColor,
        content = {
            FlatItemTextContent(
                title = {
                    val title = item.title
                        .takeUnless { it.isEmpty() }
                    if (title != null) {
                        Text(
                            text = title,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.empty_value),
                            color = LocalContentColor.current
                                .combineAlpha(DisabledEmphasisAlpha),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                },
                text = item.text
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        // composable
                        {
                            Text(
                                text = it,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                            )
                        }
                    },
            )

            if (!item.notes.isNullOrEmpty()) {
                Spacer(
                    modifier = Modifier
                        .height(4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(MediumEmphasisAlpha),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier
                            .width(14.dp),
                        imageVector = Icons.Outlined.KeyguardNote,
                        contentDescription = null,
                    )
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    Text(
                        text = item.notes,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (item.deletion != null) {
                Spacer(
                    modifier = Modifier
                        .height(4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(MediumEmphasisAlpha),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            modifier = Modifier
                                .width(14.dp),
                            imageVector = Icons.Outlined.AutoDelete,
                            contentDescription = null,
                        )
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                        Text(
                            text = item.deletion,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(
                        modifier = Modifier
                            .width(16.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            modifier = Modifier
                                .width(14.dp),
                            imageVector = Icons.Outlined.KeyguardView,
                            contentDescription = null,
                        )
                        Spacer(
                            modifier = Modifier
                                .width(8.dp),
                        )
                        Text(
                            text = item.source.accessCount.toString() +
                                    item.source.maxAccessCount?.let { " / $it" }
                                        .orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            if (expiredState.value || item.source.disabled) {
                Spacer(
                    modifier = Modifier
                        .height(4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (item.source.disabled) {
                        SmallInfoBadge(
                            text = stringResource(Res.string.deactivated),
                        )
                    }
                    if (expiredState.value) {
                        SmallInfoBadge(
                            text = stringResource(Res.string.expired),
                        )
                    }
                }
            }
        },
        leading = {
            AccountListItemTextIcon(
                send = item.source,
                item = item,
                expiredState = expiredState,
            )
        },
        trailing = {
            Spacer(modifier = Modifier.width(8.dp))

            val showCheckbox = when {
                localState.selectableItemState.selecting -> true
                else -> false
            }
            Crossfade(
                modifier = Modifier
                    .size(
                        width = 36.dp,
                        height = 36.dp,
                    ),
                targetState = showCheckbox,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showCheckbox) {
                        Checkbox(
                            checked = localState.selectableItemState.selected,
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

@Composable
private fun SmallInfoBadge(
    modifier: Modifier = Modifier,
    text: String,
) {
    val elevation = LocalAbsoluteTonalElevation.current
    Text(
        modifier = modifier
            .widthIn(max = 128.dp)
            .background(
                MaterialTheme.colorScheme
                    .surfaceColorAtElevationSemi(elevation + 2.dp),
                MaterialTheme.shapes.small,
            )
            .padding(
                horizontal = 6.dp,
                vertical = 2.dp,
            ),
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
fun AccountListItemTextIcon(
    modifier: Modifier = Modifier,
    send: DSend,
    item: SendItem.Item,
    expiredState: State<Boolean>,
) {
    val active = !send.disabled && !expiredState.value
    val accent = rememberSecretAccentColor(
        accentLight = item.accentLight,
        accentDark = item.accentDark,
    )
    AvatarBuilder(
        modifier = modifier,
        icon = item.icon,
        accent = accent,
        active = active,
        badge = {
            if (item.hasPassword) {
                AvatarBadgeIcon(
                    imageVector = Icons.Outlined.Key,
                )
            }
        },
    )
}
