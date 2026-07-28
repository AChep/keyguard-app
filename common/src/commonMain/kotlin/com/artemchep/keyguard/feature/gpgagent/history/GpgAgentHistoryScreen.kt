package com.artemchep.keyguard.feature.gpgagent.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.skeleton.SkeletonSection
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
fun GpgAgentHistoryScreen(
    cipherId: String?,
) {
    val loadableState = produceGpgAgentHistoryState(
        cipherId = cipherId,
    )
    GpgAgentHistoryScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpgAgentHistoryScreen(
    loadableState: Loadable<GpgAgentHistoryState>,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    ToolbarTitle(
                        subtitle = loadableState.getOrNull()?.subtitle,
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    loadableState.fold(
                        ifLoading = {
                            // Empty
                        },
                        ifOk = { state ->
                            OptionsButton(state.options)
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        provideContentUserScrollEnabled = {
            loadableState !is Loadable.Loading
        },
    ) {
        loadableState.fold(
            ifLoading = {
                populateGpgAgentHistorySkeleton()
            },
            ifOk = { state ->
                populateGpgAgentHistoryContent(
                    items = state.items,
                )
            },
        )
    }
}

private fun LazyListScope.populateGpgAgentHistorySkeleton() {
    item("skeleton.section") {
        SkeletonSection()
    }
    skeletonItems(
        count = 12,
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.populateGpgAgentHistoryContent(
    items: ImmutableList<GpgAgentHistoryItem>,
) {
    if (items.isEmpty()) {
        item("empty") {
            EmptyView()
        }
    }
    items(
        items = items,
        key = { it.id },
    ) { item ->
        GpgAgentHistoryItem(
            modifier = Modifier
                .animateItem(),
            item = item,
        )
    }
}

@Composable
private fun ToolbarTitle(
    subtitle: String?,
) = Column {
    if (!subtitle.isNullOrBlank()) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
        )
        Text(
            text = stringResource(Res.string.gpg_agent_history_header_title),
            style = MaterialTheme.typography.titleMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    } else {
        Text(
            text = stringResource(Res.string.gpg_agent_history_header_title),
        )
    }
}

@Composable
private fun GpgAgentHistoryItem(
    modifier: Modifier,
    item: GpgAgentHistoryItem,
) = when (item) {
    is GpgAgentHistoryItem.Section -> GpgAgentHistorySectionItem(
        modifier = modifier,
        item = item,
    )

    is GpgAgentHistoryItem.Value -> GpgAgentHistoryValueItem(
        modifier = modifier,
        item = item,
    )
}

@Composable
private fun GpgAgentHistorySectionItem(
    modifier: Modifier,
    item: GpgAgentHistoryItem.Section,
) {
    Section(
        modifier = modifier,
        text = item.text,
        caps = item.caps,
    )
}

@Composable
private fun GpgAgentHistoryValueItem(
    modifier: Modifier,
    item: GpgAgentHistoryItem.Value,
) {
    FlatDropdownSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        leading = {
            val icon = when (item.request) {
                GpgUsageHistoryRequestType.AGENT_LIST_KEYS -> Icons.Stub
                GpgUsageHistoryRequestType.AGENT_SIGN_HASH -> Icons.Outlined.Key
                GpgUsageHistoryRequestType.AGENT_DECRYPT -> Icons.Outlined.LockOpen
                GpgUsageHistoryRequestType.UNKNOWN -> Icons.Stub
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = item.caller,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                text = {
                    Column {
                        Text(
                            text = item.description,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.formattedDate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        trailing = {
            Text(
                modifier = Modifier
                    .widthIn(max = 96.dp),
                text = item.responseText,
                color = item.responseColor(),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        enabled = true,
    )
}

@Composable
private fun GpgAgentHistoryItem.Value.responseColor() = when (response) {
    GpgUsageHistoryResponseType.SUCCESS -> MaterialTheme.colorScheme.ok
    GpgUsageHistoryResponseType.USER_DENIED,
    GpgUsageHistoryResponseType.KEY_NOT_FOUND,
    GpgUsageHistoryResponseType.VAULT_LOCKED,
    GpgUsageHistoryResponseType.UNSUPPORTED,
    GpgUsageHistoryResponseType.FAILURE,
        -> MaterialTheme.colorScheme.error

    GpgUsageHistoryResponseType.UNKNOWN -> MaterialTheme.colorScheme.onSurface
}
