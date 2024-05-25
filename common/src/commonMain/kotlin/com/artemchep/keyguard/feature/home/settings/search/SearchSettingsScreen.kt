package com.artemchep.keyguard.feature.home.settings.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptySearchView
import com.artemchep.keyguard.feature.home.vault.component.SearchTextField
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultProgressBar
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.pulltosearch.PullToSearch
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.CustomToolbar
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay

@Composable
fun SearchSettingsScreen() {
    val state = produceSearchSettingsState()
    SearchSettingsScreenContent(
        state = state,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchSettingsScreenContent(
    state: SearchSettingsState,
) {
    val focusRequester = remember {
        FocusRequester2()
    }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = false,
        onRefresh = {
            focusRequester.requestFocus()
        },
    )
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(focusRequester) {
        delay(500L)
        focusRequester.requestFocus()
    }

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
                    Row(
                        modifier = Modifier
                            .heightIn(min = 64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.width(4.dp))
                        NavigationIcon()
                        Spacer(Modifier.width(4.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .align(Alignment.CenterVertically),
                        ) {
                            Text(
                                text = stringResource(Res.string.settingssearch_header_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current
                                    .combineAlpha(MediumEmphasisAlpha),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                            )
                            Text(
                                text = stringResource(Res.string.settingssearch_header_title),
                                style = MaterialTheme.typography.titleMedium,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }

                    val count = state.items.size
                    SearchTextField(
                        modifier = Modifier
                            .focusRequester2(focusRequester),
                        text = state.query.state.value,
                        placeholder = stringResource(Res.string.settingssearch_search_placeholder),
                        searchIcon = false,
                        count = count,
                        leading = {},
                        trailing = {},
                        onTextChange = state.query.onChange,
                        onGoClick = null,
                    )
                }
            }
        },
        pullRefreshState = pullRefreshState,
        overlay = {
            DefaultProgressBar(
                modifier = Modifier,
                visible = false,
            )

            PullToSearch(
                modifier = Modifier
                    .padding(contentPadding.value),
                pullRefreshState = pullRefreshState,
            )
        },
    ) {
        val items = state.items
        if (items.isEmpty()) {
            item("empty") {
                EmptySearchView()
            }
        }

        items(
            items = items,
            key = { it.key },
        ) {
            when (it) {
                is SearchSettingsState.Item.Settings -> it.content()
            }
        }
    }
}
