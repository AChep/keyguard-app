package com.artemchep.keyguard.feature.cliagent.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.agent.history.contentColor
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
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
fun CliAgentHistoryScreen(
    client: CliAgentUsageHistoryClient?,
    cipherId: String?,
) {
    val loadableState = produceCliAgentHistoryState(
        client = client,
        cipherId = cipherId,
    )
    CliAgentHistoryScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CliAgentHistoryScreen(
    loadableState: Loadable<CliAgentHistoryState>,
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
                populateCliAgentHistorySkeleton()
            },
            ifOk = { state ->
                populateCliAgentHistoryContent(
                    items = state.items,
                )
            },
        )
    }
}

private fun LazyListScope.populateCliAgentHistorySkeleton() {
    item("skeleton.section") {
        SkeletonSection()
    }
    skeletonItems(
        count = 12,
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.populateCliAgentHistoryContent(
    items: ImmutableList<CliAgentHistoryItem>,
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
        CliAgentHistoryItem(
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
            text = stringResource(Res.string.pref_item_agent_history_title),
            style = MaterialTheme.typography.titleMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    } else {
        Text(
            text = stringResource(Res.string.pref_item_agent_history_title),
        )
    }
}

@Composable
private fun CliAgentHistoryItem(
    modifier: Modifier,
    item: CliAgentHistoryItem,
) = when (item) {
    is CliAgentHistoryItem.Section -> Section(
        modifier = modifier,
        text = item.text,
        caps = item.caps,
    )

    is CliAgentHistoryItem.Value -> CliAgentHistoryValueItem(
        modifier = modifier,
        item = item,
    )
}

@Composable
private fun CliAgentHistoryValueItem(
    modifier: Modifier,
    item: CliAgentHistoryItem.Value,
) {
    FlatDropdownSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        leading = {
            Icon(
                imageVector = item.request.icon(),
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
                        if (item.commandText != null) {
                            Text(
                                text = item.commandText,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
        onClick = item.onClick,
        enabled = true,
    )
}

private fun CliAgentUsageHistoryRequestType.icon(): ImageVector = when (this) {
    CliAgentUsageHistoryRequestType.RUN -> Icons.Outlined.Terminal
    CliAgentUsageHistoryRequestType.MCP_AUTHENTICATE,
    CliAgentUsageHistoryRequestType.MCP_LIST_ENVIRONMENTS,
    CliAgentUsageHistoryRequestType.MCP_CREATE_ENVIRONMENT,
    CliAgentUsageHistoryRequestType.MCP_RENAME_ENVIRONMENT,
    CliAgentUsageHistoryRequestType.MCP_LIST_VARIABLES,
    CliAgentUsageHistoryRequestType.MCP_APPEND_VARIABLES,
    CliAgentUsageHistoryRequestType.MCP_CREATE_LOCAL_ENV_FILE,
    CliAgentUsageHistoryRequestType.MCP_LIST_LOCAL_ENV_FILES,
    -> Icons.Outlined.SmartToy

    CliAgentUsageHistoryRequestType.READ_LOCAL_ENV_FILE -> Icons.Outlined.Description
    CliAgentUsageHistoryRequestType.UNKNOWN -> Icons.Stub
}

@Composable
private fun CliAgentHistoryItem.Value.responseColor() = response
    .outcome()
    .contentColor()
