package com.artemchep.keyguard.feature.apppicker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.flatMap
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.ErrorView
import com.artemchep.keyguard.feature.favicon.FaviconImage
import com.artemchep.keyguard.feature.home.vault.component.SearchTextField
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevation
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.search.sort.SortButton
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.pulltosearch.PullToSearch
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.toolbar.CustomToolbar
import com.artemchep.keyguard.ui.toolbar.content.CustomToolbarContent
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.withIndex

@Composable
fun AppPickerScreen(
    transmitter: RouteResultTransmitter<AppPickerResult>,
) {
    val loadableState = produceAppPickerState(
        transmitter = transmitter,
    )
    ChangePasswordScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChangePasswordScreen(
    loadableState: Loadable<AppPickerState>,
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
        // Scroll to the start of the list if the list has
        // no real content.
        if (listRevision == null) {
            listState.scrollToItem(0, 0)
            return@LaunchedEffect
        }

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
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            CustomToolbar(
                scrollBehavior = scrollBehavior,
            ) {
                Column {
                    CustomToolbarContent(
                        title = stringResource(Res.string.apppicker_header_title),
                        icon = {
                            NavigationIcon()
                        },
                        actions = {
                            val content = loadableState.getOrNull()
                                ?: return@CustomToolbarContent
                            val sort by content.sort.collectAsState()
                            AppPickerSortButton(
                                state = sort,
                            )
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
                        modifier = Modifier
                            .focusRequester2(focusRequester),
                        text = queryText,
                        placeholder = stringResource(Res.string.apppicker_search_placeholder),
                        searchIcon = false,
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
                            AppItem(
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
private fun AppPickerSortButton(
    modifier: Modifier = Modifier,
    state: AppPickerState.Sort,
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
private fun NoItemsPlaceholder(
    modifier: Modifier = Modifier,
) {
    EmptySearchView(
        modifier = modifier,
        text = {
            Text(
                text = stringResource(Res.string.apppicker_empty_label),
            )
        },
    )
}

@Composable
private fun AppItem(
    modifier: Modifier,
    item: AppPickerState.Item,
) {
    FlatItem(
        modifier = modifier,
        leading = {
            FaviconImage(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape),
                imageModel = {
                    item.icon
                },
            )
        },
        title = {
            Text(item.name)
        },
        text = {
            Text(item.text)
        },
        trailing = {
            if (item.system) {
                val elevation = LocalAbsoluteTonalElevation.current
                Text(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme
                                .surfaceColorAtElevation(elevation + 2.dp),
                            MaterialTheme.shapes.small,
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 2.dp,
                        ),
                    text = stringResource(Res.string.apppicker_system_app_label),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        onClick = item.onClick,
    )
}
