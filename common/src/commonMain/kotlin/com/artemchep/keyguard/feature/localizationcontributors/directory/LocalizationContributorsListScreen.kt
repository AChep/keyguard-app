package com.artemchep.keyguard.feature.localizationcontributors.directory

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.flatMap
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.ErrorView
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.SearchTextField
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.ChevronIcon
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
fun LocalizationContributorsListScreen(
) {
    val loadableState = produceLocalizationContributorsListState()
    LocalizationContributorsListScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocalizationContributorsListScreen(
    loadableState: Loadable<LocalizationContributorsListState>,
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
                        title = stringResource(Res.string.localization_contributors_title),
                        icon = {
                            NavigationIcon()
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
                        placeholder = stringResource(Res.string.localization_contributors_search_placeholder),
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
                            LocalizationContributorItem(
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
    EmptySearchView(
        modifier = modifier,
        text = {
            Text(
                text = stringResource(Res.string.localization_contributors_empty_label),
            )
        },
    )
}

@Composable
private fun LocalizationContributorItem(
    modifier: Modifier,
    item: LocalizationContributorsListState.Item,
) {
    FlatItemSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        leading = {
            BadgedBox(
                badge = {
                    if (item.index <= 2) {
                        LocalizationContributorCrown(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape),
                            index = item.index,
                        )
                    }
                },
            ) {
                item.icon()
            }
        },
        title = {
            Text(item.name)
        },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.data.languages.forEach { language ->
                    Text(
                        text = language.name,
                    )
                }
            }
        },
        trailing = {
            Text(
                modifier = Modifier
                    .align(Alignment.CenterVertically),
                text = item.score.toString(),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            ChevronIcon(
                modifier = Modifier
                    .align(Alignment.CenterVertically),
            )
        },
        onClick = item.onClick,
    )
}

@Composable
fun LocalizationContributorCrown(
    modifier: Modifier = Modifier,
    index: Int,
) {
    val backgroundColor = when (index) {
        0 -> Color(0xFFEFBF04)
        1 -> Color(0xFFC4C4C4)
        else -> Color(0xFFCE8946)
    }
    val contentColor = Color(0xFF000000)
    Icon(
        modifier = modifier
            .background(backgroundColor)
            .padding(2.dp),
        imageVector = Icons.Outlined.Star,
        contentDescription = null,
        tint = contentColor,
    )
}
