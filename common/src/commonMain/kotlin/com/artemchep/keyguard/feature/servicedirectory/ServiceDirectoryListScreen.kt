package com.artemchep.keyguard.feature.servicedirectory

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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import arrow.core.Either
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.flatMap
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.ErrorView
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.vault.component.SearchTextField
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.RequestLazyListScrollOnRevision
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.pulltosearch.PullToSearch
import com.artemchep.keyguard.ui.skeleton.SkeletonItemAvatar
import com.artemchep.keyguard.ui.skeleton.SkeletonSection
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.toolbar.CustomToolbar
import com.artemchep.keyguard.ui.toolbar.content.CustomToolbarContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun <State, Filter, Content, Item, ContentItem> ServiceDirectoryListScaffold(
    loadableState: Loadable<State>,
    title: String,
    url: String,
    searchPlaceholder: String,
    errorText: String,
    filter: (State) -> StateFlow<Filter>,
    content: (State) -> Loadable<Either<Throwable, Content>>,
    filterRevision: (Filter) -> Int,
    filterQuery: (Filter) -> TextFieldModel,
    contentRevision: (Content) -> Int,
    contentItems: (Content) -> List<Item>,
    itemKey: (Item) -> String,
    itemContentType: (Item) -> String,
    sectionNameOrNull: (Item) -> String?,
    contentItemOrNull: (Item) -> ContentItem?,
    noItems: @Composable (Modifier) -> Unit,
    contentItem: @Composable (Modifier, ContentItem) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val filterState = run {
        val filterFlow = loadableState.getOrNull()?.let(filter)
        remember(filterFlow) {
            filterFlow ?: MutableStateFlow(null)
        }.collectAsState()
    }

    val listRevision = loadableState
        .getOrNull()
        ?.let(content)
        ?.getOrNull()
        ?.getOrNull()
        ?.let(contentRevision)
    val listState = remember {
        LazyListState(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )
    }

    RequestLazyListScrollOnRevision(
        listState = listState,
        revision = listRevision,
    )

    val focusRequester = remember { FocusRequester2() }
    LaunchedEffect(
        focusRequester,
        filterState,
    ) {
        snapshotFlow { filterState.value }
            .first { it?.let(filterQuery)?.onChange != null }
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
                        title = title,
                        icon = {
                            NavigationIcon()
                        },
                        actions = {
                            val updatedNavigationController by rememberUpdatedState(
                                LocalNavigationController.current,
                            )
                            IconButton(
                                onClick = {
                                    val intent = NavigationIntent.NavigateToBrowser(url)
                                    updatedNavigationController.queue(intent)
                                },
                            ) {
                                IconBox(Icons.Outlined.OpenInBrowser)
                            }
                        },
                    )

                    val query = filterState.value?.let(filterQuery)
                    val queryText = query?.text.orEmpty()

                    val count = loadableState
                        .getOrNull()
                        ?.let(content)
                        ?.getOrNull()
                        ?.getOrNull()
                        ?.let(contentItems)
                        ?.size
                    SearchTextField(
                        modifier = Modifier,
                        text = queryText,
                        textRevision = query?.textRevision,
                        placeholder = searchPlaceholder,
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
            val currentFilterRevision = filterState.value?.let(filterRevision)
            DefaultProgressBar(
                visible = listRevision != null && currentFilterRevision != null &&
                        listRevision != currentFilterRevision,
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
            .flatMap(content)
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
                                    Text(text = errorText)
                                },
                                exception = e,
                            )
                        }
                    },
                    ifRight = { listContent ->
                        val items = contentItems(listContent)
                        if (items.isEmpty()) {
                            item("empty") {
                                noItems(Modifier)
                            }
                        }

                        items(
                            items = items,
                            key = itemKey,
                            contentType = itemContentType,
                        ) { item ->
                            val serviceItem = contentItemOrNull(item)
                            if (serviceItem != null) {
                                contentItem(
                                    Modifier.animateItem(),
                                    serviceItem,
                                )
                            } else {
                                val sectionName = sectionNameOrNull(item)
                                if (sectionName != null) {
                                    Section(
                                        text = sectionName,
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
