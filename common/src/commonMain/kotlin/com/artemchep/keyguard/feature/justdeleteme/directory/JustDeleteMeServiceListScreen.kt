package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.URL_JUST_DELETE_ME
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.flatMap
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.ErrorView
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.SearchTextField
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.justdeleteme.AhDifficulty
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.pulltosearch.PullToSearch
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.skeleton.SkeletonItemAvatar
import com.artemchep.keyguard.ui.skeleton.SkeletonSection
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.toolbar.CustomToolbar
import com.artemchep.keyguard.ui.toolbar.content.CustomToolbarContent
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.withIndex

@Composable
fun JustDeleteMeListScreen(
) {
    val loadableState = produceJustDeleteMeServiceListState()
    JustDeleteMeListScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun JustDeleteMeListScreen(
    loadableState: Loadable<JustDeleteMeServiceListState>,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val filterState = run {
        val filterFlow = loadableState.getOrNull()?.filter
        remember(filterFlow) {
            filterFlow ?: MutableStateFlow(null)
        }.collectAsState()
    }

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

    val focusRequester = remember { FocusRequester2() }
    // Auto focus the text field
    // on launch.
    LaunchedEffect(
        focusRequester,
        filterState,
    ) {
        snapshotFlow { filterState.value }
            .first { it?.query?.onChange != null }
        delay(100L)
        focusRequester.requestFocus()
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = false,
        onRefresh = {
            focusRequester.requestFocus()
        },
    )
    ScaffoldLazyColumn(
        modifier = Modifier
            .pullRefresh(pullRefreshState)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            CustomToolbar(
                scrollBehavior = scrollBehavior,
            ) {
                Column {
                    CustomToolbarContent(
                        title = stringResource(Res.string.justdeleteme_title),
                        icon = {
                            NavigationIcon()
                        },
                        actions = {
                            val updatedNavigationController by rememberUpdatedState(
                                LocalNavigationController.current,
                            )
                            IconButton(
                                onClick = {
                                    val intent =
                                        NavigationIntent.NavigateToBrowser(URL_JUST_DELETE_ME)
                                    updatedNavigationController.queue(intent)
                                },
                            ) {
                                IconBox(Icons.Outlined.OpenInBrowser)
                            }
                        },
                    )

                    val query = filterState.value?.query
                    val queryText = query?.state?.value.orEmpty()

                    val count = loadableState
                        .getOrNull()
                        ?.content
                        ?.getOrNull()
                        ?.getOrNull()
                        ?.items
                        ?.size
                    SearchTextField(
                        modifier = Modifier,
                        text = queryText,
                        placeholder = stringResource(Res.string.justdeleteme_search_placeholder),
                        searchIcon = false,
                        focusRequester = focusRequester,
                        focusFlow = query?.focusFlow,
                        count = count,
                        leading = {},
                        trailing = {},
                        onTextChange = query?.onChange,
                    )
                }
            }
        },
        pullRefreshState = pullRefreshState,
        overlay = {
            val filterRevision = filterState.value?.revision
            DefaultProgressBar(
                visible = listRevision != null && filterRevision != null &&
                        listRevision != filterRevision,
            )

            PullToSearch(
                modifier = Modifier
                    .padding(contentPadding.value),
                pullRefreshState = pullRefreshState,
            )
        },
        provideContentUserScrollEnabled = {
            loadableState !is Loadable.Loading
        },
        listState = listState,
    ) {
        val contentState = loadableState
            .flatMap { it.content }
        when (contentState) {
            is Loadable.Loading -> {
                item("skeleton.section") {
                    SkeletonSection()
                }
                skeletonItems(
                    avatar = SkeletonItemAvatar.SMALL,
                    textWidthFraction = null,
                    count = 20,
                )
            }

            is Loadable.Ok -> {
                contentState.value.fold(
                    ifLeft = { e ->
                        item("error") {
                            ErrorView(
                                text = {
                                    Text(text = "Failed to load app list!")
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
                            when (item) {
                                is JustDeleteMeServiceListState.Item.Content -> {
                                    AppItem(
                                        modifier = Modifier
                                            .animateItem(),
                                        item = item,
                                    )
                                }
                                is JustDeleteMeServiceListState.Item.Section -> {
                                    Section(
                                        text = item.name,
                                    )
                                }
                            }
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
    EmptySearchView(
        modifier = modifier,
        text = {
            Text(
                text = stringResource(Res.string.justdeleteme_empty_label),
            )
        },
    )
}

@Composable
private fun AppItem(
    modifier: Modifier,
    item: JustDeleteMeServiceListState.Item.Content,
) {
    val backgroundColor = run {
        if (LocalHasDetailPane.current) {
            val nextEntry = navigationNextEntryOrNull()
            val nextRoute = nextEntry?.route as? JustDeleteMeServiceViewFullRoute

            val selected = nextRoute?.args?.justDeleteMe?.name == item.name.text
            if (selected) {
                return@run MaterialTheme.colorScheme.selectedContainer
            }
        }

        Color.Unspecified
    }
    FlatItemSimpleExpressive(
        modifier = modifier,
        backgroundColor = backgroundColor,
        shapeState = item.shapeState,
        leading = {
            item.icon()
        },
        title = {
            Text(item.name)
        },
        trailing = {
            AhDifficulty(
                modifier = Modifier,
                model = item.data,
            )
        },
        onClick = item.onClick,
    )
}
