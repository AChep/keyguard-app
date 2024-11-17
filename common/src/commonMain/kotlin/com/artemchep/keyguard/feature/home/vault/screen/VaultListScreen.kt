package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.component.AddAccountView
import com.artemchep.keyguard.feature.home.vault.component.VaultListItem
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.LocalNavigationRouter
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.search.filter.FilterButton
import com.artemchep.keyguard.feature.search.filter.FilterScreen
import com.artemchep.keyguard.feature.search.filter.component.FilterItemComposable
import com.artemchep.keyguard.feature.search.sort.SortButton
import com.artemchep.keyguard.feature.twopane.TwoPaneScreen
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DropdownMenuExpandableContainer
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.Compose
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.DropdownScopeImpl
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.SmallFab
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.pulltosearch.PullToSearch
import com.artemchep.keyguard.ui.skeleton.SkeletonFilter
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.toolbar.CustomToolbar
import com.artemchep.keyguard.ui.toolbar.content.CustomSearchbarContent
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.withIndex

@Composable
fun VaultListScreen(
    args: VaultRoute.Args,
) {
    val state = vaultListScreenState(
        args = args,
        highlightBackgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
        highlightContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        mode = LocalAppMode.current,
    )
    val old = remember {
        mutableStateOf<VaultListState?>(null)
    }
    SideEffect {
        old.value = state
    }

    val wtf = (state.content as? VaultListState.Content.Items)?.onSelected
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
                val firstRoute = backStack.firstOrNull()?.route as? VaultViewRoute?
                wtf?.invoke(firstRoute?.itemId)
            }
        }
    }

    val xd by rememberUpdatedState(LocalNavigationEntry.current.id)
    val controller by rememberUpdatedState(LocalNavigationController.current)
    CollectedEffect(state.sideEffects.showBiometricPromptFlow) {
        val route = VaultViewRoute(
            itemId = it.id,
            accountId = it.accountId,
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(xd),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        controller.queue(intent)
//        bs.push(route)
    }

    val focusRequester = remember { FocusRequester2() }
    TwoPaneScreen(
        header = { modifier ->
            CustomSearchbarContent(
                modifier = modifier,
                searchFieldModifier = Modifier
                    .focusRequester2(focusRequester),
                searchFieldModel = state.query,
                searchFieldPlaceholder = stringResource(Res.string.vault_main_search_placeholder),
                title = args.appBar?.title,
                subtitle = args.appBar?.subtitle,
                icon = {
                    NavigationIcon()
                },
                actions = {
                    VaultListSortButton(
                        state = state,
                    )
                    OptionsButton(
                        actions = state.actions,
                    )
                },
            )
        },
        detail = { modifier ->
            VaultListFilterScreen(
                modifier = modifier,
                state = state,
            )
        },
    ) { modifier, tabletUi ->
        VaultHomeScreenListPane(
            modifier = modifier,
            state = state,
            focusRequester = focusRequester,
            title = args.appBar?.title,
            subtitle = args.appBar?.subtitle,
            fab = args.canAddSecrets,
            tabletUi = tabletUi,
            preselect = args.preselect,
        )
    }

    LaunchedEffect(state.showKeyboard) {
        if (state.showKeyboard) {
            delay(200L) // FIXME: Delete this!
            focusRequester.requestFocus()
        }
    }
}

@Composable
private fun VaultListFilterScreen(
    modifier: Modifier = Modifier,
    state: VaultListState,
) {
    val count = (state.content as? VaultListState.Content.Items)?.count
    val filters = state.filters
    val clearFilters = state.clearFilters
    val saveFilters = state.saveFilters
    FilterScreen(
        modifier = modifier,
        count = count,
        items = filters,
        onClear = clearFilters,
        onSave = saveFilters,
    )
}

@Composable
private fun VaultListFilterButton(
    modifier: Modifier = Modifier,
    state: VaultListState,
) {
    val count = (state.content as? VaultListState.Content.Items)?.count
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
private fun VaultListSortButton(
    modifier: Modifier = Modifier,
    state: VaultListState,
) {
    val filters = state.sort
    val clearFilters = state.clearSort
    SortButton(
        modifier = modifier,
        items = filters,
        onClear = clearFilters,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalAnimationApi::class,
    ExperimentalMaterialApi::class,
)
@Composable
fun VaultHomeScreenListPane(
    modifier: Modifier,
    state: VaultListState,
    focusRequester: FocusRequester2,
    title: String?,
    subtitle: String?,
    fab: Boolean,
    tabletUi: Boolean,
    preselect: Boolean,
) {
    val itemsState = (state.content as? VaultListState.Content.Items)

    val listRevision = itemsState?.revision
    val listState = remember {
        LazyListState(
            firstVisibleItemIndex = listRevision?.firstVisibleItemIndex?.value ?: 0,
            firstVisibleItemScrollOffset = listRevision?.firstVisibleItemScrollOffset?.value ?: 0,
        )
    }

    LaunchedEffect(listRevision?.id) {
        // Scroll to the start of the list if the list has
        // no real content.
        if (listRevision == null) {
            listState.scrollToItem(0, 0)
            return@LaunchedEffect
        }

        // TODO: How do you wait till the layout state start to represent
        //  the actual data?
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .withIndex()
            .filter {
                it.index > 0 || it.value == itemsState.list.size
            }
            .first()

        val index = listRevision.firstVisibleItemIndex.value
        val offset = listRevision.firstVisibleItemScrollOffset.value
        listState.scrollToItem(index, offset)
    }

    LaunchedEffect(listRevision?.onScroll) {
        if (listRevision == null) return@LaunchedEffect // do nothing, just cancel old job

        val visibleItemScrollStateFlow = snapshotFlow {
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            index to offset
        }
        visibleItemScrollStateFlow.collect { (index, offset) ->
            // TODO: How do you wait till the layout state start to represent
            //  the actual data?
            if (listState.layoutInfo.totalItemsCount != itemsState.list.size) {
                return@collect
            }

            listRevision.onScroll(
                index,
                offset,
            )
        }
    }

    val dp = remember {
        mutableStateOf(false)
    }
    val rp = remember {
        mutableStateOf(false)
    }
    if (state.primaryActions.isEmpty()) {
        dp.value = false
    }
    val updatedSelectCipher by rememberUpdatedState(newValue = state.selectCipher)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pullRefreshState = rememberPullRefreshState(
        refreshing = false,
        onRefresh = {
            focusRequester.requestFocus()
        },
    )
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
                    searchFieldPlaceholder = stringResource(Res.string.vault_main_search_placeholder),
                    title = title,
                    subtitle = subtitle,
                    icon = {
                        NavigationIcon()
                    },
                    actions = {
                        VaultListFilterButton(
                            state = state,
                        )
                        VaultListSortButton(
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
            val fabVisible = fab && state.primaryActions.isNotEmpty()
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
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SmallFab(
                    onClick = {
                        rp.value = true
                    },
                    icon = {
                        IconBox(main = Icons.Outlined.History)

                        RecentsButtonRaw(
                            visible = rp,
                            onDismissRequest = {
                                rp.value = false
                            },
                            onValueChange = { cipher ->
                                updatedSelectCipher?.invoke(cipher)
                            },
                        )
                    },
                )
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
                                DropdownMenuExpandableContainer(
                                    list = state.primaryActions,
                                ) { action ->
                                    DropdownMenuItemFlat(
                                        action = action,
                                    )
                                }
                            }
                        }
                    },
                    text = {
                        Text(
                            text = stringResource(Res.string.vault_main_new_item_button),
                        )
                    },
                )
            }
        },
        pullRefreshState = pullRefreshState,
        bottomBar = {
            val selectionOrNull = (state.content as? VaultListState.Content.Items)?.selection
            DefaultSelection(
                state = selectionOrNull,
            )
        },
        overlay = {
            DefaultProgressBar(
                modifier = Modifier,
                visible = state.content is VaultListState.Content.Items &&
                        state.revision != state.content.revision.id,
            )

            PullToSearch(
                modifier = Modifier
                    .padding(contentPadding.value),
                pullRefreshState = pullRefreshState,
            )
        },
        listState = listState,
    ) {
        when (state.content) {
            is VaultListState.Content.Skeleton -> {
                item {
                    Column(
                        modifier = Modifier,
                    ) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (i in 1..5) {
                                SkeletonFilter()
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
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
                if (state.content is VaultListState.Content.AddAccount) {
                    item("header.add_account") {
                        AddAccountView(
                            onClick = state.content.onAddAccount,
                        )
                    }
                } else {
                }

                val list = (state.content as? VaultListState.Content.Items)?.list.orEmpty()
                items(
                    items = list,
                    key = { model -> model.id },
                ) { model ->
                    if (model is VaultItem2.QuickFilters && !tabletUi) {
                        Box(
                            modifier = Modifier
                                .animateItem(),
                        ) {
                            val arg2 = LocalAppMode.current
                            val llll = remember(state.filters) {
                                state.filters
                                    .filter { it.sectionId == "custom" && it is FilterItem.Item }
                                    .map { it as FilterItem.Item }
                            }
                            if (llll.isNotEmpty() && fab && arg2 is AppMode.Main && state.query.state.value == "") Column(
                                modifier = Modifier,
                            ) {
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    llll.forEach { item ->
                                        FilterItemComposable(
                                            leading = item.leading,
                                            title = item.title,
                                            text = null,
                                            checked = item.checked,
                                            onClick = item.onClick,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    } else {
                        VaultListItem(
                            modifier = Modifier
                                .animateItem(),
                            item = model,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoItemsPlaceholder() {
}
