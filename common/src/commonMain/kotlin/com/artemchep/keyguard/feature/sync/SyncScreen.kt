package com.artemchep.keyguard.feature.sync

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.Ah
import com.artemchep.keyguard.ui.AhContainer
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardCipher
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.badgeContainer
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.stringResource

@Composable
fun SyncScreen() {
    val loadableState = produceSyncState()
    SyncContent(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncContent(
    loadableState: Loadable<SyncState>,
) {
    val s = loadableState.fold(
        ifLoading = {
            remember {
                mutableStateOf(emptyList())
            }
        },
        ifOk = {
            it.itemsFlow.collectAsState()
        },
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.syncstatus_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        itemsIndexed(
            items = s.value,
            key = { _, it -> it.key },
        ) { index, item ->
            if (index > 0) {
                HorizontalDivider()
            }
            TestK(
                modifier = Modifier
                    .animateItemPlacement(),
                item = item,
            )
        }
    }
}

@Composable
private fun TestK(
    modifier: Modifier = Modifier,
    item: SyncState.Item,
) {
    Column(
        modifier = modifier,
    ) {
        FlatItemLayout(
            leading = {
                when (item.status) {
                    is SyncState.Item.Status.Ok,
                    is SyncState.Item.Status.Pending,
                    -> {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.ok,
                        )
                    }

                    is SyncState.Item.Status.Failed -> {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            content = {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = item.email,
                        )
                    },
                    text = {
                        Text(
                            text = item.host,
                        )
                    },
                )
            },
            onClick = item.onClick,
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp),
        ) {
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            when (item.status) {
                is SyncState.Item.Status.Ok -> {
                    BadgeStatusUpToDate()
                }

                is SyncState.Item.Status.Pending -> {
                    BadgeStatusPending(
                        badge = item.status.text,
                    )
                }

                is SyncState.Item.Status.Failed -> {
                    BadgeStatusFailed()
                }
            }
            if (item.lastSyncTimestamp != null) {
                Spacer(
                    modifier = Modifier
                        .height(4.dp),
                )
                Text(
                    text = item.lastSyncTimestamp,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
            }
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
        }
        item.items.forEach { action ->
            when (action) {
                is FlatItemAction -> {
                    FlatItem(
                        leading = {
                            Icon(
                                Icons.Outlined.KeyguardCipher,
                                null,
                            )
                        },
                        trailing = {
                            ChevronIcon()
                        },
                        title = {
                            Text(
                                text = textResource(action.title),
                            )
                        },
                        elevation = 1.dp,
                        onClick = action.onClick,
                    )
                }

                else -> {
                }
            }
        }
    }
}

//
// Badges
//

@Composable
private fun BadgeStatusUpToDate(
    modifier: Modifier = Modifier,
) {
    Ah(
        modifier = modifier,
        score = 1f,
        text = stringResource(Res.string.syncstatus_status_up_to_date),
    )
}

@Composable
private fun BadgeStatusPending(
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Box(
        modifier = modifier,
    ) {
        AhContainer(
            modifier = Modifier
                .matchParentSize()
                .shimmer(),
            score = 1f,
        ) {
            // Do nothing.
        }
        Row(
            modifier = Modifier
                .padding(
                    start = 4.dp,
                    top = 4.dp,
                    bottom = 4.dp,
                    end = 4.dp,
                )
                .widthIn(min = 36.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                modifier = Modifier
                    .padding(horizontal = 4.dp),
                badge = {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.badgeContainer,
                    ) {
                        Text(
                            modifier = Modifier
                                .animateContentSize(),
                            text = badge.orEmpty(),
                        )
                    }
                },
            ) {
                Text(
                    modifier = Modifier
                        .animateContentSize(),
                    text = "Pending",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun BadgeStatusFailed(
    modifier: Modifier = Modifier,
) {
    Ah(
        modifier = modifier,
        score = 0f,
        text = stringResource(Res.string.syncstatus_status_failed),
    )
}
