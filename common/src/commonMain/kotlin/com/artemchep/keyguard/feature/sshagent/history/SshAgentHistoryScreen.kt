package com.artemchep.keyguard.feature.sshagent.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
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
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
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
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
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
fun SshAgentHistoryScreen(
    cipherId: String?,
) {
    val loadableState = produceSshAgentHistoryState(
        cipherId = cipherId,
    )
    SshAgentHistoryScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshAgentHistoryScreen(
    loadableState: Loadable<SshAgentHistoryState>,
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
                populateSshAgentHistorySkeleton()
            },
            ifOk = { state ->
                populateSshAgentHistoryContent(
                    items = state.items,
                )
            },
        )
    }
}

private fun LazyListScope.populateSshAgentHistorySkeleton() {
    item("skeleton.section") {
        SkeletonSection()
    }
    skeletonItems(
        count = 12,
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.populateSshAgentHistoryContent(
    items: ImmutableList<SshAgentHistoryItem>,
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
        SshAgentHistoryItem(
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
            text = stringResource(Res.string.ssh_agent_history_header_title),
            style = MaterialTheme.typography.titleMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    } else {
        Text(
            text = stringResource(Res.string.ssh_agent_history_header_title),
        )
    }
}

@Composable
private fun SshAgentHistoryItem(
    modifier: Modifier,
    item: SshAgentHistoryItem,
) = when (item) {
    is SshAgentHistoryItem.Section -> SshAgentHistorySectionItem(
        modifier = modifier,
        item = item,
    )

    is SshAgentHistoryItem.Value -> SshAgentHistoryValueItem(
        modifier = modifier,
        item = item,
    )
}

@Composable
private fun SshAgentHistorySectionItem(
    modifier: Modifier,
    item: SshAgentHistoryItem.Section,
) {
    Section(
        modifier = modifier,
        text = item.text,
        caps = item.caps,
    )
}

@Composable
private fun SshAgentHistoryValueItem(
    modifier: Modifier,
    item: SshAgentHistoryItem.Value,
) {
    FlatDropdownSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        leading = {
            val icon = when (item.request) {
                SshUsageHistoryRequestType.AGENT_LIST_KEYS -> Icons.Stub
                SshUsageHistoryRequestType.AGENT_SIGN_DATA -> Icons.Outlined.KeyguardSshKey
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
private fun SshAgentHistoryItem.Value.responseColor() = when (response) {
    SshUsageHistoryResponseType.SUCCESS -> MaterialTheme.colorScheme.ok
    SshUsageHistoryResponseType.USER_DENIED,
    SshUsageHistoryResponseType.KEY_NOT_FOUND,
    SshUsageHistoryResponseType.FAILURE,
        -> MaterialTheme.colorScheme.error
}
