package com.artemchep.keyguard.feature.filter.list

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.flatMap
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.ErrorView
import com.artemchep.keyguard.feature.filter.view.CipherFilterViewFullRoute
import com.artemchep.keyguard.feature.generator.wordlist.view.WordlistViewRoute
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.SearchTextField
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasDynamicShortcuts
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.AvatarBuilder
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.defaultAvatarColor
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.pulltosearch.PullToSearch
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.skeleton.SkeletonItemAvatar
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
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
fun CipherFiltersListScreen(
) {
    val loadableState = produceCipherFiltersListState()
    CipherFiltersListScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CipherFiltersListScreen(
    loadableState: Loadable<CipherFiltersListState>,
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
                        title = stringResource(Res.string.customfilters_header_title),
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
                        placeholder = stringResource(Res.string.customfilters_search_placeholder),
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
        bottomBar = {
            when (loadableState) {
                is Loadable.Ok -> {
                    val selectionOrNull by loadableState.value.selection.collectAsState()
                    DefaultSelection(
                        state = selectionOrNull,
                    )
                }
                is Loadable.Loading -> {
                    // Do nothing
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
                skeletonItems(
                    avatar = SkeletonItemAvatar.LARGE,
                )
            }

            is Loadable.Ok -> {
                contentState.value.fold(
                    ifLeft = { e ->
                        item("error") {
                            ErrorView(
                                text = {
                                    Text(text = "Failed to load filters list!")
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
                            FilterItem(
                                modifier = Modifier
                                    .animateItem(),
                                item = item,
                            )
                        }
                    },
                )
            }
        }

        if (CurrentPlatform.hasDynamicShortcuts()) item("note") {
            Spacer(
                modifier = Modifier
                    .height(32.dp),
            )
            Icon(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding),
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
            )
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding),
                text = stringResource(Res.string.customfilters_dynamic_shortcut_tip),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current
                    .combineAlpha(alpha = MediumEmphasisAlpha),
            )
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
private fun FilterItem(
    modifier: Modifier,
    item: CipherFiltersListState.Item,
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
                val nextRoute = nextEntry?.route as? CipherFilterViewFullRoute

                val selected = nextRoute?.args?.model?.id == item.key
                if (selected) {
                    return@run MaterialTheme.colorScheme.selectedContainer
                }
            }

            Color.Unspecified
        }
    }
    FlatItemSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        backgroundColor = backgroundColor,
        leading = {
            AvatarBuilder(
                icon = item.icon,
                accent = defaultAvatarColor(),
                active = true,
                badge = {
                    // Do nothing.
                },
            )
        },
        title = {
            Text(item.name)
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
