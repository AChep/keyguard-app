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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.component.AddAccountView
import com.artemchep.keyguard.feature.home.vault.component.VaultListItem
import com.artemchep.keyguard.feature.home.vault.component.rememberSearchQueryHighlightVisualTransformation
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.LocalNavigationRouter
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.keyboard.isPlainEscape
import com.artemchep.keyguard.feature.search.filter.FilterButton
import com.artemchep.keyguard.feature.search.filter.FilterScreen
import com.artemchep.keyguard.feature.search.filter.component.FilterChipItemComposable
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
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.RequestLazyListScrollOnRevision
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.SmallFab
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.ListFocus
import com.artemchep.keyguard.ui.focus.ListFocusRegion
import com.artemchep.keyguard.ui.focus.enterAt
import com.artemchep.keyguard.ui.focus.focusFirstResultOnDown
import com.artemchep.keyguard.ui.focus.rememberFabFocusOrder
import com.artemchep.keyguard.ui.focus.rememberListFocus
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.pulltosearch.PullToSearch
import com.artemchep.keyguard.ui.pulltosearch.pullToSearch
import com.artemchep.keyguard.ui.skeleton.SkeletonFilter
import com.artemchep.keyguard.ui.skeleton.SkeletonItemAvatar
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.CustomToolbar
import com.artemchep.keyguard.ui.toolbar.content.CustomSearchbarContent
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay

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

    val openItemIdState = remember {
        mutableStateOf<String?>(null)
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
                // Shortcuts reach the top entry first. A link in an
                // item opens another item on top of it.
                val topRoute = backStack.lastOrNull { it.route is VaultViewRoute }
                    ?.route as? VaultViewRoute
                openItemIdState.value = topRoute?.itemId
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
            focusFirstField = true,
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

    VaultListScreenContent(
        args = args,
        state = state,
        openItemId = { openItemIdState.value },
    )
}

/**
 * The vault list of the [state]. [openItemId] is the id of the cipher
 * of the top item view next to the list, if any. Its shortcuts, such
 * as Delete, act on that cipher.
 */
@Composable
internal fun VaultListScreenContent(
    args: VaultRoute.Args,
    state: VaultListState,
    openItemId: () -> String?,
) {
    val focusRequester = remember { FocusRequester2() }
    val searchFieldRequester = remember { FocusRequester() }

    val listRevision = (state.content as? VaultListState.Content.Items)?.revision
    val listState = remember {
        LazyListState(
            firstVisibleItemIndex = listRevision?.firstVisibleItemIndex?.value ?: 0,
            firstVisibleItemScrollOffset = listRevision?.firstVisibleItemScrollOffset?.value ?: 0,
        )
    }
    val listKeys = remember(state.content) {
        state.content.listKeys()
    }
    // Opening the Vault tab moves no focus, coming
    // back to it restores the row the user left from.
    val focus = rememberListFocus(
        listState = listState,
        listKeys = listKeys,
        focusFirstOnEnter = false,
        exitUp = searchFieldRequester,
    )
    val searchRegion = focus.rememberRegion()
    val paneRegion = focus.rememberRegion()
    val listModifier = rememberVaultListKeyModifier(
        state = state,
        focus = focus,
        searchFieldRequester = searchFieldRequester,
        openItemId = openItemId,
    )
    val searchFieldModifier = Modifier
        .focusRequester(searchFieldRequester)
        .then(searchRegion.modifier)
        .focusFirstResultOnDown(
            state = listState,
            firstResult = focus.listRequester,
            firstIndex = 0.takeIf { listKeys.isNotEmpty() },
            group = true,
        )

    TwoPaneScreen(
        header = { modifier ->
            val subtitle = textResource(args.appBar?.subtitle)
            val queryVisualTransformation = rememberSearchQueryHighlightVisualTransformation(
                state.queryHighlighting,
            )
            CustomSearchbarContent(
                modifier = modifier,
                searchFieldModifier = Modifier,
                searchFieldInputModifier = searchFieldModifier,
                searchFieldModel = state.query,
                searchFieldPlaceholder = stringResource(Res.string.vault_main_search_placeholder),
                focusRequester = focusRequester,
                searchFieldVisualTransformation = queryVisualTransformation,
                searchFieldQualifierSuggestion = state.queryQualifierSuggestion,
                onSearchFieldQualifierSuggestion = state.onQueryQualifierSuggestion,
                title = args.appBar?.title,
                subtitle = subtitle,
                playPromo = true,
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
                region = paneRegion,
            )
        },
    ) { modifier, tabletUi ->
        val subtitle = textResource(args.appBar?.subtitle)
        VaultHomeScreenListPane(
            modifier = modifier,
            state = state,
            listState = listState,
            listModifier = listModifier,
            focus = focus,
            searchFieldModifier = searchFieldModifier,
            focusRequester = focusRequester,
            title = args.appBar?.title,
            subtitle = subtitle,
            fab = args.canAddSecrets,
            tabletUi = tabletUi,
            preselect = args.preselect,
        )
    }

    LaunchedEffect(state.showKeyboard) {
        if (state.showKeyboard) {
            delay(200L) // FIXME: Delete this!
            // The keyboard may have moved focus to the list, the filters
            // or an item by now. A focused search field still requests
            // focus, as that shows the soft keyboard.
            val keepFocus = focus.anyHasFocus && !searchRegion.hasFocus
            if (!keepFocus && !focus.detailOpen) {
                focusRequester.requestFocus()
            }
        }
    }
}

private const val ADD_ACCOUNT_KEY = "header.add_account"

/** The keys of the lazy items of the list, in order. */
private fun VaultListState.Content.listKeys(): List<String> = when (this) {
    is VaultListState.Content.Items -> object : AbstractList<String>() {
        override val size: Int get() = list.size

        override fun get(index: Int): String = list[index].id
    }

    is VaultListState.Content.AddAccount -> listOf(ADD_ACCOUNT_KEY)
    // The skeleton rows take no focus.
    else -> emptyList()
}

/**
 * The [ListFocus.listModifier] with the keys of the vault list:
 * - Delete does nothing on a row other than the open item, as the
 *   top item view would delete the open item;
 * - Escape with a query moves focus to the search field before
 *   Back clears the query, so focus stays on screen.
 */
@Composable
private fun rememberVaultListKeyModifier(
    state: VaultListState,
    focus: ListFocus,
    searchFieldRequester: FocusRequester,
    openItemId: () -> String?,
): Modifier {
    val entry = LocalNavigationEntry.current
    val inputModeManager = LocalInputModeManager.current
    val updatedState by rememberUpdatedState(state)
    val updatedOpenItemId by rememberUpdatedState(openItemId)
    return remember(focus, entry, inputModeManager, searchFieldRequester) {
        focus.listModifier.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when {
                event.key == Key.Delete || event.key == Key.Backspace -> {
                    val openId = updatedOpenItemId()
                        ?: return@onKeyEvent false
                    // The list holds focus, so the focused row is the last one.
                    val focusedKey = entry.viewMemory.lastFocusedKey
                    val focusedItem = (updatedState.content as? VaultListState.Content.Items)
                        ?.list
                        ?.firstOrNull { it.id == focusedKey } as? VaultItem2.Item
                    focusedItem?.source?.id != openId
                }

                event.isPlainEscape -> {
                    val keyboard = inputModeManager.inputMode == InputMode.Keyboard
                    if (keyboard && updatedState.query.text.isNotEmpty() && !focus.detailOpen) {
                        searchFieldRequester.requestFocus()
                    }
                    // Back clears the query.
                    false
                }

                else -> false
            }
        }
    }
}

@Composable
private fun VaultListFilterScreen(
    modifier: Modifier = Modifier,
    state: VaultListState,
    region: ListFocusRegion,
) {
    val count = (state.content as? VaultListState.Content.Items)?.count
    val filters = state.filters
    val clearFilters = state.clearFilters
    val saveFilters = state.saveFilters
    FilterScreen(
        modifier = modifier
            .then(region.modifier),
        count = count,
        items = filters,
        onClear = clearFilters,
        onSave = saveFilters,
        onFocusContent = region::focusList,
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
    ExperimentalMaterialApi::class, ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
private fun VaultHomeScreenListPane(
    modifier: Modifier,
    state: VaultListState,
    listState: LazyListState,
    listModifier: Modifier,
    focus: ListFocus,
    searchFieldModifier: Modifier,
    focusRequester: FocusRequester2,
    title: String?,
    subtitle: String?,
    fab: Boolean,
    tabletUi: Boolean,
    preselect: Boolean,
) {
    val itemsState = (state.content as? VaultListState.Content.Items)

    val listRevision = itemsState?.revision
    RequestLazyListScrollOnRevision(
        listState = listState,
        revision = listRevision?.id,
        index = listRevision?.firstVisibleItemIndex?.value ?: 0,
        offset = listRevision?.firstVisibleItemScrollOffset?.value ?: 0,
    )

    LaunchedEffect(listRevision?.onScroll) {
        if (listRevision == null) return@LaunchedEffect // do nothing, just cancel old job

        val visibleItemScrollStateFlow = snapshotFlow {
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            val key = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == index }
                ?.key
            Triple(index, offset, key)
        }
        visibleItemScrollStateFlow.collect { (index, offset, key) ->
            val itemKey = itemsState.list
                .getOrNull(index)
                ?.id
                ?: return@collect
            if (key != itemKey) {
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
    val queryVisualTransformation = rememberSearchQueryHighlightVisualTransformation(
        state.queryHighlighting,
    )

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pullRefreshState = rememberPullRefreshState(
        refreshing = false,
        onRefresh = {
            focusRequester.requestFocus()
        },
    )
    ScaffoldLazyColumn(
        modifier = modifier
            .pullToSearch(pullRefreshState)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
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
                    searchFieldModifier = Modifier,
                    searchFieldInputModifier = searchFieldModifier,
                    searchFieldModel = state.query,
                    searchFieldPlaceholder = stringResource(Res.string.vault_main_search_placeholder),
                    focusRequester = focusRequester,
                    searchFieldVisualTransformation = queryVisualTransformation,
                    searchFieldQualifierSuggestion = state.queryQualifierSuggestion,
                    onSearchFieldQualifierSuggestion = state.onQueryQualifierSuggestion,
                    title = title,
                    subtitle = subtitle,
                    playPromo = true,
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
            val (recentsFabModifier, newItemFabModifier) = rememberFabFocusOrder(count = 2)
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SmallFab(
                    modifier = recentsFabModifier,
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
                    modifier = newItemFabModifier,
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
                        KeyguardDropdownMenu(
                            expanded = dp.value,
                            onDismissRequest = onDismissRequest,
                        ) {
                            DropdownMenuExpandableContainer(
                                dropdownScope = this,
                                list = state.primaryActions,
                            ) { action ->
                                DropdownMenuItemFlat(
                                    action = action,
                                )
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
        provideContentUserScrollEnabled = {
            state.content !is VaultListState.Content.Skeleton
        },
        listModifier = listModifier,
        listState = listState,
        contentFocus = focus.rememberScaffoldContentFocus(),
    ) {
        when (state.content) {
            is VaultListState.Content.Skeleton -> {
                if (!tabletUi) item {
                    Column(
                        modifier = Modifier,
                    ) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.contentPadding),
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
                skeletonItems(
                    avatar = SkeletonItemAvatar.LARGE,
                    count = 20,
                )
            }

            else -> {
                if (state.content is VaultListState.Content.AddAccount) {
                    item(ADD_ACCOUNT_KEY) {
                        AddAccountView(
                            modifier = focus.target(ADD_ACCOUNT_KEY),
                            onClick = state.content.onAddAccount,
                        )
                    }
                } else {
                }

                val list = (state.content as? VaultListState.Content.Items)?.list.orEmpty()
                items(
                    items = list,
                    key = { model -> model.id },
                    contentType = { model -> model.contentType },
                ) { model ->
                    if (model is VaultItem2.QuickFilters && !tabletUi) {
                        val firstChipRequester = remember { FocusRequester() }
                        Box(
                            modifier = Modifier
                                .animateItem()
                                // Down would enter at the top-left chip, the
                                // last one of a right-to-left line.
                                .enterAt(firstChipRequester, listOf(FocusDirection.Down))
                                .then(focus.target(model.id)),
                        ) {
                            val arg2 = LocalAppMode.current
                            val llll = remember(state.filters) {
                                state.filters
                                    .filter { it.sectionId == "custom" && it is FilterItem.ChipItem }
                                    .map { it as FilterItem.ChipItem }
                            }
                            val firstChipIndex = llll.indexOfFirst { it.onClick != null }
                            if (llll.isNotEmpty() && fab && arg2 is AppMode.Main && state.query.text == "") Column(
                                modifier = Modifier,
                            ) {
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Dimens.contentPadding),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    llll.forEachIndexed { index, item ->
                                        FilterChipItemComposable(
                                            modifier = if (index == firstChipIndex) {
                                                Modifier.focusRequester(firstChipRequester)
                                            } else {
                                                Modifier
                                            },
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
                                .animateItem()
                                .then(focus.target(model.id)),
                            item = model,
                        )
                    }
                }
            }
        }
    }
}
