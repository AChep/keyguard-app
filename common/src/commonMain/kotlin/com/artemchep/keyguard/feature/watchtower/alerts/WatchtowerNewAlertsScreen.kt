package com.artemchep.keyguard.feature.watchtower.alerts

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.component.VaultListItemText
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRoute
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AhLayout
import com.artemchep.keyguard.ui.AvatarBadgeIcon
import com.artemchep.keyguard.ui.AvatarBadgeSurface
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DefaultSelection
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.badgeContainer
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.infoContainer
import com.artemchep.keyguard.ui.theme.onInfoContainer
import com.artemchep.keyguard.ui.theme.onWarningContainer
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.theme.warning
import com.artemchep.keyguard.ui.theme.warningContainer
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import org.jetbrains.compose.resources.stringResource

@Composable
fun WatchtowerNewAlerts(
    args: WatchtowerAlertsRoute.Args,
) {
    val loadableState = produceGeneratorHistoryState(
        args = args,
    )
    GeneratorPaneMaster(
        modifier = Modifier,
        loadableState = loadableState,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterialApi::class,
)
@Composable
private fun GeneratorPaneMaster(
    modifier: Modifier,
    loadableState: Loadable<WatchtowerNewAlertsState>,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.watchtower_alerts_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            val selectionOrNull = loadableState.getOrNull()?.selection
            DefaultSelection(
                state = selectionOrNull,
            )
        },
        floatingActionState = run {
            val fabOnClick = loadableState.getOrNull()?.onMarkAllRead
            val fabState = if (fabOnClick != null) {
                FabState(
                    onClick = fabOnClick,
                    model = null,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    IconBox(main = Icons.Outlined.Check)
                },
                text = {
                    Text(
                        text = stringResource(Res.string.watchtower_mark_all_as_read_title),
                    )
                },
            )
        },
    ) {
        loadableState.fold(
            ifLoading = {
                populateGeneratorPaneMasterSkeleton()
            },
            ifOk = { state ->
                populateGeneratorPaneMasterContent(
                    state = state,
                )
            },
        )
    }
}

private fun LazyListScope.populateGeneratorPaneMasterSkeleton() {
    item("skeleton") {
        SkeletonItem()
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.populateGeneratorPaneMasterContent(
    state: WatchtowerNewAlertsState,
) {
    if (state.items.isEmpty()) {
        item("empty") {
            EmptyView()
        }
    }
    items(state.items, key = { it.id }) { item ->
        GeneratorHistoryItem(
            modifier = Modifier
                .animateItemPlacement(),
            item = item,
        )
    }
}

@Composable
private fun GeneratorHistoryItem(
    modifier: Modifier,
    item: WatchtowerNewAlertsState.Item,
) = when (item) {
    is WatchtowerNewAlertsState.Item.Alert -> GeneratorHistoryItem(modifier, item)
    is WatchtowerNewAlertsState.Item.Section -> VaultListItemSection(modifier, item)
}

@Composable
private fun GeneratorHistoryItem(
    modifier: Modifier,
    item: WatchtowerNewAlertsState.Item.Alert,
) {
    val backgroundColor = run {
        if (LocalHasDetailPane.current) {
            val nextEntry = navigationNextEntryOrNull()
            val nextRoute = nextEntry?.route as? VaultViewRoute

            val selected = nextRoute?.tag == item.id
            if (selected) {
                return@run MaterialTheme.colorScheme.selectedContainer
            }
        }

        Color.Unspecified
    }
    VaultListItemText(
        modifier = modifier,
        item = item.item,
        leading = { composable ->
            BadgedBox(
                badge = {
                    androidx.compose.animation.AnimatedVisibility(
                        modifier = Modifier
                            .size(16.dp),
                        visible = !item.read,
                        enter = fadeIn() + scaleIn(),
                        exit = scaleOut() + fadeOut(),
                    ) {
                        AvatarBadgeSurface(
                            modifier = Modifier,
                            backgroundColor = MaterialTheme.colorScheme.badgeContainer,
                        ) {
                            AvatarBadgeIcon(
                                imageVector = Icons.Outlined.Info,
                            )
                        }
                    }
                },
            ) {
                composable()
            }
        },
        content = {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ahhhh(
                    modifier = Modifier,
                    type = item.type,
                )
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
                Text(
                    text = item.date,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
            }
        },
    )
}

@Composable
private fun Ahhhh(
    modifier: Modifier = Modifier,
    type: DWatchtowerAlertType,
) {
    val tintColor = when (type.level) {
        DWatchtowerAlertType.Level.INFO -> MaterialTheme.colorScheme.info
        DWatchtowerAlertType.Level.WARNING -> MaterialTheme.colorScheme.warning
        DWatchtowerAlertType.Level.ERROR -> MaterialTheme.colorScheme.error
    }
    val surfaceColor = when (type.level) {
        DWatchtowerAlertType.Level.INFO -> MaterialTheme.colorScheme.infoContainer
        DWatchtowerAlertType.Level.WARNING -> MaterialTheme.colorScheme.warningContainer
        DWatchtowerAlertType.Level.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (type.level) {
        DWatchtowerAlertType.Level.INFO -> MaterialTheme.colorScheme.onInfoContainer
        DWatchtowerAlertType.Level.WARNING -> MaterialTheme.colorScheme.onWarningContainer
        DWatchtowerAlertType.Level.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    AhLayout(
        modifier = modifier,
        contentColor = contentColor,
        backgroundColor = surfaceColor
            .combineAlpha(DisabledEmphasisAlpha),
    ) {
        val imageVector = when (type.level) {
            DWatchtowerAlertType.Level.INFO -> Icons.Outlined.Info
            DWatchtowerAlertType.Level.WARNING -> Icons.Outlined.Warning
            DWatchtowerAlertType.Level.ERROR -> Icons.Outlined.ErrorOutline
        }
        Icon(
            modifier = Modifier
                .size(14.dp),
            imageVector = imageVector,
            contentDescription = null,
            tint = tintColor,
        )
        Spacer(
            modifier = Modifier
                .width(4.dp),
        )
        Text(
            text = stringResource(type.title),
        )
        Spacer(
            modifier = Modifier
                .width(4.dp),
        )
    }
}

@Composable
fun VaultListItemSection(
    modifier: Modifier = Modifier,
    item: WatchtowerNewAlertsState.Item.Section,
) {
    val text = item.text?.let { textResource(it) }
    Section(
        modifier = modifier,
        text = text,
        caps = item.caps,
    )
}
