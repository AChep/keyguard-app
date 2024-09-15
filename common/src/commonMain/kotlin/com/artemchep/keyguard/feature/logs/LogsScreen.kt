package com.artemchep.keyguard.feature.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.infoContainer
import com.artemchep.keyguard.ui.theme.onInfoContainer
import com.artemchep.keyguard.ui.theme.onWarningContainer
import com.artemchep.keyguard.ui.theme.warningContainer
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun LogsScreen() {
    val modifier = Modifier
    val scrollBehavior = ToolbarBehavior.behavior()

    val loadableState = produceLogsState()
    loadableState.fold(
        ifLoading = {
            LogsScreenSkeleton(
                modifier = modifier,
                scrollBehavior = scrollBehavior,
            )
        },
        ifOk = { state ->
            LogsScreenContent(
                modifier = modifier,
                scrollBehavior = scrollBehavior,
                state = state,
            )
        },
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class,
)
@Composable
private fun LogsScreenSkeleton(
    modifier: Modifier,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ScaffoldLazyColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.logs_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        item("skeleton") {
            SkeletonItem()
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class,
)
@Composable
private fun LogsScreenContent(
    modifier: Modifier,
    scrollBehavior: TopAppBarScrollBehavior,
    state: LogsState,
) {
    val contentState = state.contentFlow.collectAsState()
    ScaffoldLazyColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.logs_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    val exportState = state.exportFlow.collectAsState()
                    TextButton(
                        enabled = exportState.value.onExportClick != null,
                        onClick = {
                            exportState.value.onExportClick?.invoke()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SaveAlt,
                            contentDescription = null,
                        )
                        Spacer(
                            modifier = Modifier
                                .width(Dimens.buttonIconPadding),
                        )
                        Text(
                            text = stringResource(Res.string.logs_export_button),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val switchState = state.switchFlow.collectAsState()
            val fabState = FabState(
                onClick = switchState.value.onToggle,
                model = switchState.value.checked,
            )
            rememberUpdatedState(fabState)
        },
        floatingActionButton = {
            val checked = this.state.value?.model == true
            DefaultFab(
                icon = {
                    Icon(
                        imageVector = if (!checked) {
                            Icons.Outlined.PlayArrow
                        } else {
                            Icons.Outlined.Stop
                        },
                        contentDescription = null,
                    )
                },
            ) {
                Text(
                    text = if (!checked) {
                        stringResource(Res.string.logs_start_recording_fab_title)
                    } else {
                        stringResource(Res.string.logs_stop_recording_fab_title)
                    },
                )
            }
        },
    ) {
        val items = contentState.value.items
        if (items.isEmpty()) {
            item("empty") {
                EmptyView()
            }
        }
        items(items, key = { it.id }) { item ->
            LogItem(
                modifier = Modifier
                    .animateItem(),
                item = item,
            )
        }
    }
}

@Composable
private fun LogItem(
    modifier: Modifier,
    item: LogsItem,
) = when (item) {
    is LogsItem.Section -> LogItem(
        modifier = modifier,
        item = item,
    )

    is LogsItem.Value -> LogItem(
        modifier = modifier,
        item = item,
    )
}

@Composable
private fun LogItem(
    modifier: Modifier,
    item: LogsItem.Section,
) {
    Section(
        modifier = modifier,
        text = item.text,
        caps = item.caps,
    )
}

@Composable
private fun LogItem(
    modifier: Modifier,
    item: LogsItem.Value,
) {
    FlatItemLayout(
        modifier = modifier,
        content = {
            Row {
                val levelContainerColor = when (item.level) {
                    LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
                    LogLevel.WARNING -> MaterialTheme.colorScheme.warningContainer
                    LogLevel.INFO -> MaterialTheme.colorScheme.infoContainer
                    LogLevel.DEBUG -> MaterialTheme.colorScheme.surfaceContainer
                }
                val levelContentColor = when (item.level) {
                    LogLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                    LogLevel.WARNING -> MaterialTheme.colorScheme.onWarningContainer
                    LogLevel.INFO -> MaterialTheme.colorScheme.onInfoContainer
                    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurface
                }
                Box(
                    modifier = Modifier
                        .background(levelContainerColor, RoundedCornerShape(4.dp))
                        .size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.level.letter,
                        color = levelContentColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Spacer(
                    modifier = Modifier
                        .width(16.dp),
                )

                Column {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = item.time,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                }
            }
        },
        enabled = true,
    )
}
