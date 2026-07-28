package com.artemchep.keyguard.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemColors
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailItemDefaults
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AllowScreenshots
import com.artemchep.keyguard.common.model.DAccountStatus
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.usecase.GetAccountStatus
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import com.artemchep.keyguard.common.usecase.GetNavItemsConfig
import com.artemchep.keyguard.common.usecase.GetNavLabel
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadCount
import com.artemchep.keyguard.common.usecase.PutAllowScreenshots
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.feature.home.navigation.HomeNavigationItem
import com.artemchep.keyguard.feature.home.navigation.homeGeneratorRoute
import com.artemchep.keyguard.feature.home.navigation.homeVaultRoute
import com.artemchep.keyguard.feature.home.navigation.resolveHomeNavigationItems
import com.artemchep.keyguard.feature.home.navigation.splitHomeNavigationItemsByMinSize
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeVisualStack
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.sync.SyncRoute
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.platform.leDisplayCutout
import com.artemchep.keyguard.platform.leIme
import com.artemchep.keyguard.platform.leNavigationBars
import com.artemchep.keyguard.platform.leStatusBars
import com.artemchep.keyguard.platform.leSystemBars
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AnimatedCounterBadge
import com.artemchep.keyguard.ui.AnimatedNewCounterBadge
import com.artemchep.keyguard.ui.AnimatedTotalCounterBadge
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.infoContainer
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.onWarningContainer
import com.artemchep.keyguard.ui.theme.warningContainer
import com.artemchep.keyguard.ui.time.rememberLocalizedRelativeTime
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kodein.di.compose.rememberInstance

private const val ROUTE_NAME = "home"

// Min size restriction is used to collapse extra items into
// a dedicated overflow item when needed.
private val bottomNavigationItemMinSize = 64.dp
private val railNavigationItemMinSize = 64.dp

@Composable
fun HomeScreen(
    navBarVisible: Boolean = true,
) {
    val deeplinkService by rememberInstance<DeeplinkService>()
    val defaultRoute = remember {
        val defaultHome = deeplinkService.get(DeeplinkService.CUSTOM_HOME)
        when (defaultHome) {
            "generator" -> homeGeneratorRoute
            else -> homeVaultRoute
        }
    }

    val getWatchtowerUnreadCount by rememberInstance<GetWatchtowerUnreadCount>()
    val watchtowerUnreadCountFlow = remember(getWatchtowerUnreadCount) {
        getWatchtowerUnreadCount()
            .map { count ->
                count.takeIf { it > 0 }
            }
    }

    val getNavItemsConfig by rememberInstance<GetNavItemsConfig>()
    val navItemsConfigState = remember(getNavItemsConfig) {
        getNavItemsConfig()
    }.collectAsState()

    val getCipherFilters by rememberInstance<GetCipherFilters>()
    val cipherFiltersState = remember(getCipherFilters) {
        getCipherFilters()
    }.collectAsState(emptyList())

    val navRoutes = remember(
        navItemsConfigState.value,
        cipherFiltersState.value,
        watchtowerUnreadCountFlow,
    ) {
        resolveHomeNavigationItems(
            config = navItemsConfigState.value,
            cipherFilters = cipherFiltersState.value,
            watchtowerUnreadCount = watchtowerUnreadCountFlow,
        ).toPersistentList()
    }
    NavigationRouter(
        id = ROUTE_NAME,
        initial = defaultRoute,
    ) { backStack ->
        HomeScreenContent(
            backStack = backStack,
            routes = navRoutes,
            navBarVisible = navBarVisible,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreenContent(
    backStack: PersistentList<NavigationEntry>,
    routes: ImmutableList<HomeNavigationItem>,
    navBarVisible: Boolean = true,
) {
    HomeNavigationDynamicRootEffect(
        backStack = backStack,
        routes = routes,
    )

    ResponsiveLayout {
        val maxWidth = maxWidth
        val maxHeight = maxHeight

        val horizontalInsets = WindowInsets.leStatusBars
            .union(WindowInsets.leNavigationBars)
            .union(WindowInsets.leDisplayCutout)
            .only(WindowInsetsSides.Start)
        Row(
            modifier = Modifier
                .windowInsetsPadding(horizontalInsets),
        ) {
            val putAllowScreenshots by rememberInstance<PutAllowScreenshots>()
            val getAllowScreenshots by rememberInstance<GetAllowScreenshots>()
            val allowScreenshotsState = remember(getAllowScreenshots) {
                getAllowScreenshots()
                    .map { allowScreenshots ->
                        allowScreenshots == AllowScreenshots.LIMITED
                    }
            }.collectAsState(false)

            val getNavLabel by rememberInstance<GetNavLabel>()
            val navLabelState = remember(getNavLabel) {
                getNavLabel()
            }.collectAsState()

            val getAccountStatus by rememberInstance<GetAccountStatus>()
            val accountStatusState = remember(getAccountStatus) {
                getAccountStatus()
            }.collectAsState(DAccountStatus())
            // The first row is a row that contains rail navigation
            // bar and should be shown on tablets.
            AnimatedVisibility(
                modifier = Modifier
                    .zIndex(2f),
                visible = LocalHomeLayout.current is HomeLayout.Horizontal && navBarVisible,
            ) {
                Row {
                    val scope = rememberCoroutineScope()

                    val railState = rememberWideNavigationRailState()
                    val verticalInsets = WindowInsets.leSystemBars
                        .union(WindowInsets.leIme)
                        .only(WindowInsetsSides.Vertical)

                    val canExpand = maxHeight >= 480.dp
                    LaunchedEffect(canExpand) {
                        if (!canExpand) railState.collapse()
                    }
                    val density = LocalDensity.current
                    val routesSplit = remember(
                        routes,
                        maxHeight,
                        canExpand,
                        density,
                    ) {
                        val reservedHeight = if (canExpand) 168.dp else 112.dp
                        splitHomeNavigationItemsByMinSize(
                            items = routes,
                            availableSizePx = with(density) {
                                (maxHeight - reservedHeight)
                                    .coerceAtLeast(0.dp)
                                    .roundToPx()
                            },
                            itemMinSizePx = with(density) {
                                railNavigationItemMinSize.roundToPx()
                            },
                        )
                    }

                    WideNavigationRail(
                        modifier = Modifier
                            .fillMaxHeight(),
                        state = railState,
                        colors = WideNavigationRailDefaults.colors()
                            .copy(containerColor = Color.Transparent),
                        windowInsets = verticalInsets,
                        header = if (canExpand) {
                            // composable
                            {
                                IconButton(
                                    modifier = Modifier
                                        .padding(start = 24.dp),
                                    onClick = {
                                        scope.launch {
                                            railState.toggle()
                                        }
                                    },
                                ) {
                                    if (railState.targetValue == WideNavigationRailValue.Expanded) {
                                        Icon(Icons.AutoMirrored.Filled.MenuOpen, null)
                                    } else {
                                        Icon(Icons.Filled.Menu, null)
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    ) {
                        routesSplit.visible.forEach { r ->
                            key(r.key) {
                                val counterState = r.counterFlow.collectAsState(null)
                                RailNavigationControllerItem(
                                    modifier = Modifier
                                        .homeNavigationTestTag(r.testTag),
                                    backStack = backStack,
                                    item = r,
                                    expanded = railState.currentValue == WideNavigationRailValue.Expanded,
                                    count = counterState.value,
                                    label = if (navLabelState.value) {
                                        // composable
                                        {
                                            Text(
                                                text = textResource(r.label),
                                                maxLines = 2,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        if (routesSplit.overflow.isNotEmpty()) {
                            RailNavigationOverflowItem(
                                backStack = backStack,
                                items = routesSplit.overflow,
                                expanded = railState.currentValue == WideNavigationRailValue.Expanded,
                                label = if (navLabelState.value) {
                                    {
                                        Text(
                                            text = stringResource(Res.string.options),
                                            maxLines = 2,
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                        Spacer(
                            modifier = Modifier
                                .height(24.dp),
                        )
                        Column(
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .width(IntrinsicSize.Min),
                        ) {
                            RailStatusBadge(
                                modifier = Modifier,
                                statusState = accountStatusState,
                            )
                        }
                    }
                }
            }
            val bottomInsets = WindowInsets.leStatusBars
                .union(WindowInsets.leNavigationBars)
                .union(WindowInsets.leDisplayCutout)
                .only(WindowInsetsSides.Bottom)
            val bottomNavBarVisible =
                LocalHomeLayout.current is HomeLayout.Vertical && navBarVisible
            var bottomNavBarSize by remember {
                mutableStateOf(64.dp)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (bottomNavBarVisible) {
                            val navBarInsets = bottomInsets
                                .add(WindowInsets(bottom = bottomNavBarSize))
                            Modifier.consumeWindowInsets(navBarInsets)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                val defaultContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                CompositionLocalProvider(
                    LocalSurfaceColor provides defaultContainerColor,
                    LocalNavigationNodeVisualStack provides persistentListOf(),
                ) {
                    NavigationNode(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(defaultContainerColor),
                        entries = backStack,
                    )
                }

                // TODO:
//                val onboardingBannerState = rememberOnboardingBannerState()
//                val notificationsBannerState = rememberNotificationsBannerState()
//                kotlin.run {
//                    val navigationController by rememberUpdatedState(LocalNavigationController.current)
//                    ExpandedIfNotEmpty(
//                        valueOrNull = Unit.takeIf { onboardingBannerState.value },
//                    ) {
//                        OnboardingBanner(
//                            contentModifier = Modifier
//                                .then(
//                                    if (!bottomNavBarVisible && !notificationsBannerState.value) {
//                                        Modifier
//                                            .padding(bottomInsets.asPaddingValues())
//                                    } else Modifier
//                                ),
//                            onClick = {
//                                val intent = NavigationIntent.NavigateToRoute(
//                                    route = OnboardingRoute,
//                                )
//                                navigationController.queue(intent)
//                            },
//                        )
//                    }
//                }
//                // TODO: Show notifications banner as in a bottom navigation
//                //  container if the orientation is landscape.
//                NotificationsBanner(
//                    contentModifier = Modifier
//                        .then(
//                            if (!bottomNavBarVisible) {
//                                Modifier
//                                    .padding(bottomInsets.asPaddingValues())
//                            } else Modifier
//                        ),
//                )
                AnimatedVisibility(
                    visible = allowScreenshotsState.value,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.warningContainer)
                            .clickable {
                                putAllowScreenshots(AllowScreenshots.DISABLED)
                                    .attempt()
                                    .launchIn(GlobalScope)
                            }
                            .padding(
                                horizontal = Dimens.textHorizontalPadding,
                                vertical = 8.dp,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        Icon(
                            modifier = Modifier
                                .size(16.dp),
                            imageVector = Icons.Outlined.Screenshot,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onWarningContainer,
                        )
                        Text(
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onWarningContainer,
                            text = stringResource(Res.string.pref_item_allow_screenshots_badge),
                        )
                    }
                }
                AnimatedVisibility(
                    visible = bottomNavBarVisible,
                ) {
                    val updatedDensity by rememberUpdatedState(LocalDensity.current)
                    Column(
                        modifier = Modifier
                            .padding(bottomInsets.asPaddingValues())
                            .onSizeChanged {
                                val heightDp = (it.height.toFloat() / updatedDensity.density).dp
                                bottomNavBarSize = heightDp
                                    .coerceAtLeast(64.dp)
                            },
                    ) {
                        val isSyncingState = remember(
                            accountStatusState,
                        ) {
                            derivedStateOf {
                                accountStatusState.value.error == null &&
                                        accountStatusState.value.pending != null
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isSyncingState.value,
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth(),
                            )
                        }

                        BannerStatusBadge(
                            modifier = Modifier
                                .fillMaxWidth(),
                            statusState = accountStatusState,
                        )
                        val iconPosition = if (maxWidth <= 680.dp) {
                            NavigationItemIconPosition.Top
                        } else {
                            NavigationItemIconPosition.Start
                        }
                        val density = LocalDensity.current
                        val routesSplit = remember(
                            routes,
                            maxWidth,
                            density,
                        ) {
                            splitHomeNavigationItemsByMinSize(
                                items = routes,
                                availableSizePx = with(density) {
                                    maxWidth.roundToPx()
                                },
                                itemMinSizePx = with(density) {
                                    bottomNavigationItemMinSize.roundToPx()
                                },
                            )
                        }
                        ShortNavigationBar(
                            containerColor = Color.Unspecified,
                        ) {
                            routesSplit.visible.forEach { r ->
                                key(r.key) {
                                    val counterState = r.counterFlow.collectAsState(null)
                                    BottomNavigationControllerItem(
                                        modifier = Modifier
                                            .homeNavigationTestTag(r.testTag),
                                        backStack = backStack,
                                        item = r,
                                        iconPosition = iconPosition,
                                        count = counterState.value,
                                        label = if (navLabelState.value) {
                                            // composable
                                            {
                                                Text(
                                                    text = textResource(r.label),
                                                    maxLines = 1,
                                                    textAlign = TextAlign.Center,
                                                    // Default style does not fit on devices with small
                                                    // screens.
                                                    fontSize = 10.sp,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            if (routesSplit.overflow.isNotEmpty()) {
                                BottomNavigationOverflowItem(
                                    backStack = backStack,
                                    items = routesSplit.overflow,
                                    iconPosition = iconPosition,
                                    label = if (navLabelState.value) {
                                        {
                                            Text(
                                                text = stringResource(Res.string.options),
                                                maxLines = 1,
                                                textAlign = TextAlign.Center,
                                                fontSize = 10.sp,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Keeps the selected home navigation stack aligned with dynamically resolved
 * items by refreshing its root route when the item still matches the stack but
 * now resolves to a different route instance.
 *
 * TL/DR: When a selected home tab now points to a different screen, replace
 * the stale root screen with the current one.
 */
@Composable
private fun HomeNavigationDynamicRootEffect(
    backStack: ImmutableList<NavigationEntry>,
    routes: ImmutableList<HomeNavigationItem>,
) {
    val controller = LocalNavigationController.current
    LaunchedEffect(
        backStack,
        routes,
    ) {
        val rootRoute = backStack.firstOrNull()?.route
            ?: return@LaunchedEffect
        val item = routes.firstOrNull { item ->
            item.isSelected(backStack) &&
                    rootRoute.descriptor != item.route.descriptor
        } ?: return@LaunchedEffect

        val intent = NavigationIntent.Manual { factory ->
            val navStack = getStack(
                id = item.stackId,
            )
            val rootEntry = navStack.entries.firstOrNull()
                ?: return@Manual navStack
            val shouldRefreshRoot =
                item.isSelected(navStack.entries) &&
                        rootEntry.route.descriptor != item.route.descriptor
            if (!shouldRefreshRoot) {
                return@Manual navStack
            }

            val refreshedRootEntry = factory(rootEntry.id, item.route)
            navStack.copy(
                entries = navStack.entries.set(0, refreshedRootEntry),
            )
        }
        controller.queue(intent)
    }
}

@Composable
private fun BannerStatusBadge(
    modifier: Modifier = Modifier,
    statusState: State<DAccountStatus>,
) {
    val updatedContext by rememberUpdatedState(LocalLeContext)
    val updatedNavController by rememberUpdatedState(LocalNavigationController.current)
    val errorState = remember(
        statusState,
    ) {
        derivedStateOf {
            val error = statusState.value.error
            when {
                error != null -> {
                    BannerStatusBadgeContentModel(
                        count = error.count,
                        title = TextHolder.Res(Res.string.syncstatus_status_failed),
                        error = true,
                        onClick = {
                            navigateSyncStatus(updatedNavController)
                        },
                    )
                }

                statusState.value.pendingPermissions
                    .isNotEmpty() -> {
                    BannerStatusBadgeContentModel(
                        count = 0,
                        title = TextHolder.Res(Res.string.post_notifications_permission_banner_title),
                        text = TextHolder.Res(Res.string.post_notifications_permission_banner_text),
                        error = false,
                        onClick = {
                            val permission = statusState.value.pendingPermissions
                                .firstOrNull()
                            permission?.ask?.invoke(updatedContext)
                        },
                    )
                }

                else -> null
            }
        }
    }
    ExpandedIfNotEmpty(
        modifier = modifier,
        valueOrNull = errorState.value,
    ) { currentErrorState ->
        BannerStatusBadgeContent(
            state = currentErrorState,
        )
    }
}

private data class BannerStatusBadgeContentModel(
    val count: Int,
    val title: TextHolder,
    val text: TextHolder? = null,
    val error: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun BannerStatusBadgeContent(
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    state: BannerStatusBadgeContentModel,
) {
    Row {
        val backgroundColor =
            if (state.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.infoContainer
        val contentColor =
            if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.info
        Row(
            modifier = modifier
                .weight(1f)
                .padding(top = 8.dp)
                .padding(horizontal = 8.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(backgroundColor)
                .clickable(onClick = state.onClick)
                .then(contentModifier)
                .padding(
                    vertical = 8.dp,
                    horizontal = Dimens.horizontalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = contentColor,
                )
                if (state.count > 0) {
                    BadgedBox(
                        badge = {
                            val count = state.count
                            AnimatedTotalCounterBadge(
                                count = count,
                            )
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp),
                        )
                    }
                }
            }
            Spacer(
                modifier = Modifier
                    .width(Dimens.horizontalPadding),
            )
            Column(
                modifier = Modifier
                    .weight(1f),
            ) {
                Text(
                    text = textResource(state.title),
                    style = MaterialTheme.typography.labelLarge,
                )
                ExpandedIfNotEmpty(
                    valueOrNull = state.text,
                ) { text ->
                    Text(
                        text = textResource(text),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                }
            }
            Spacer(
                modifier = Modifier
                    .width(Dimens.horizontalPadding),
            )
            ChevronIcon()
        }
    }
}

@Composable
private fun RailStatusBadge(
    modifier: Modifier = Modifier,
    statusState: State<DAccountStatus>,
) {
    val updatedContext by rememberUpdatedState(LocalLeContext)
    val updatedNavController by rememberUpdatedState(LocalNavigationController.current)
    Box(
        modifier = modifier
            .width(80.dp)
            .heightIn(min = 80.dp)
            .padding(4.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable {
                val status = statusState.value
                when {
                    status.error != null &&
                            status.pending != null -> {
                        navigateSyncStatus(updatedNavController)
                    }

                    status.pendingPermissions.isNotEmpty() -> {
                        val permission = status.pendingPermissions
                            .firstOrNull()
                        permission?.ask?.invoke(updatedContext)
                    }

                    else -> {
                        navigateSyncStatus(updatedNavController)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val status = statusState.value
        when {
            status.error != null -> {
                RailStatusBadgeContent(
                    contentColor = MaterialTheme.colorScheme.error,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                        )
                    },
                    badge = status.error.count
                        .takeIf { it > 0 }
                        ?.toString(),
                    text = stringResource(Res.string.syncstatus_status_failed),
                )
            }

            status.pending != null -> {
                RailStatusBadgeContent(
                    modifier = Modifier
                        .shimmer(),
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.CloudUpload,
                            contentDescription = null,
                        )
                    },
                    badge = status.pending.count
                        .takeIf { it > 0 }
                        ?.toString(),
                    text = stringResource(Res.string.syncstatus_status_syncing),
                )
            }

            status.pendingPermissions.isNotEmpty() -> {
                RailStatusBadgeContent(
                    contentColor = MaterialTheme.colorScheme.info,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                        )
                    },
                    text = "Pending permissions",
                )
            }

            else -> {
                val subText = status.lastSyncTimestamp
                    ?.let { rememberLocalizedRelativeTime(it) }
                RailStatusBadgeContent(
                    contentColor = MaterialTheme.colorScheme.ok,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                        )
                    },
                    text = stringResource(Res.string.syncstatus_status_up_to_date),
                    subText = subText,
                )
            }
        }
    }
}

@Composable
private fun RailStatusBadgeContent(
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
    icon: @Composable () -> Unit,
    badge: String? = null,
    text: String,
    subText: String? = null,
) {
    Column(
        modifier = modifier
            .padding(
                horizontal = 4.dp,
                vertical = 16.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BadgedBox(
            badge = {
                AnimatedCounterBadge(
                    text = badge,
                )
            },
        ) {
            CompositionLocalProvider(
                LocalContentColor provides contentColor,
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp),
                ) {
                    icon()
                }
            }
        }
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = contentColor,
        )
        if (subText != null) {
            Text(
                text = subText,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                fontSize = 9.sp,
                maxLines = 1,
                color = contentColorFor(LocalSurfaceColor.current)
                    .combineAlpha(DisabledEmphasisAlpha),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RailNavigationControllerItem(
    modifier: Modifier = Modifier,
    backStack: ImmutableList<NavigationEntry>,
    item: HomeNavigationItem,
    expanded: Boolean,
    count: Int?,
    label: @Composable (() -> Unit)? = null,
) {
    val controller = LocalNavigationController.current
    val selected = item.isSelected(backStack)
    WideNavigationRailItem(
        modifier = modifier,
        icon = {
            NavigationIcon(
                selected = selected,
                icon = item.icon,
                iconSelected = item.iconSelected,
                count = count,
            )
        },
        label = label,
        selected = selected,
        railExpanded = expanded,
        colors = navigationRailItemColors(),
        onClick = {
            navigateOnClick(controller, item)
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BottomNavigationControllerItem(
    modifier: Modifier = Modifier,
    backStack: ImmutableList<NavigationEntry>,
    item: HomeNavigationItem,
    iconPosition: NavigationItemIconPosition,
    count: Int?,
    label: @Composable (() -> Unit)? = null,
) {
    val controller = LocalNavigationController.current
    val selected = item.isSelected(backStack)
    ShortNavigationBarItem(
        modifier = modifier,
        icon = {
            NavigationIcon(
                selected = selected,
                icon = item.icon,
                iconSelected = item.iconSelected,
                count = count,
            )
        },
        label = label,
        selected = selected,
        iconPosition = iconPosition,
        colors = navigationBarItemColors(),
        onClick = {
            navigateOnClick(controller, item)
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RailNavigationOverflowItem(
    backStack: ImmutableList<NavigationEntry>,
    items: List<HomeNavigationItem>,
    expanded: Boolean,
    label: @Composable (() -> Unit)? = null,
) {
    val controller = LocalNavigationController.current
    var menuExpanded by remember {
        mutableStateOf(false)
    }
    val selected = items.any { item ->
        item.isSelected(backStack)
    }
    val count = rememberOverflowCount(items)
    Box {
        WideNavigationRailItem(
            icon = {
                NavigationIcon(
                    selected = selected,
                    icon = Icons.Filled.MoreVert,
                    iconSelected = Icons.Filled.MoreVert,
                    count = count,
                )
            },
            label = label,
            selected = selected,
            railExpanded = expanded,
            colors = navigationRailItemColors(),
            onClick = {
                menuExpanded = true
            },
        )
        HomeNavigationOverflowMenu(
            expanded = menuExpanded,
            onDismissRequest = {
                menuExpanded = false
            },
            items = items,
            backStack = backStack,
            onItemClick = { item ->
                menuExpanded = false
                navigateOnClick(controller, item)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BottomNavigationOverflowItem(
    backStack: ImmutableList<NavigationEntry>,
    items: List<HomeNavigationItem>,
    iconPosition: NavigationItemIconPosition,
    label: @Composable (() -> Unit)? = null,
) {
    val updatedController by rememberUpdatedState(LocalNavigationController.current)

    var menuExpanded by remember {
        mutableStateOf(false)
    }
    val selected = items.any { item ->
        item.isSelected(backStack)
    }
    val count = rememberOverflowCount(items)
    Box {
        ShortNavigationBarItem(
            icon = {
                NavigationIcon(
                    selected = selected,
                    icon = Icons.Filled.MoreHoriz,
                    iconSelected = Icons.Filled.MoreHoriz,
                    count = count,
                )
            },
            label = label,
            selected = selected,
            iconPosition = iconPosition,
            colors = navigationBarItemColors(),
            onClick = {
                menuExpanded = true
            },
        )
        HomeNavigationOverflowMenu(
            expanded = menuExpanded,
            onDismissRequest = {
                menuExpanded = false
            },
            items = items,
            backStack = backStack,
            onItemClick = { item ->
                menuExpanded = false
                navigateOnClick(updatedController, item)
            },
        )
    }
}

@Composable
private fun HomeNavigationOverflowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<HomeNavigationItem>,
    backStack: ImmutableList<NavigationEntry>,
    onItemClick: (HomeNavigationItem) -> Unit,
) {
    KeyguardDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        val updatedOnClick by rememberUpdatedState(onItemClick)
        val actions = remember(items, backStack) {
            items
                .map { item ->
                    val selected = item.isSelected(backStack)
                    FlatItemAction(
                        id = item.key,
                        icon = if (selected) item.iconSelected else item.icon,
                        selected = selected,
                        title = item.label,
                        onClick = {
                            updatedOnClick.invoke(item)
                        },
                    )
                }
        }
        actions.forEach { action ->
            key(action.id) {
                DropdownMenuItemFlat(
                    action = action,
                )
            }
        }
    }
}

@Composable
private fun rememberOverflowCount(
    items: List<HomeNavigationItem>,
): Int? {
    val countState = remember(items) {
        items
            .map { it.counterFlow }
            .combineToList()
            .map { counters ->
                counters.sumOf { it ?: 0 }
                    .takeIf { it > 0 }
            }
    }.collectAsState(null)
    return countState.value
}

private fun Modifier.homeNavigationTestTag(
    testTag: String?,
): Modifier = if (testTag != null) {
    testTag(testTag)
} else {
    this
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun navigationRailItemColors(): NavigationItemColors {
    return WideNavigationRailItemDefaults.colors()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun navigationBarItemColors(): NavigationItemColors {
    return ShortNavigationBarItemDefaults.colors()
}

@Composable
private fun NavigationIcon(
    selected: Boolean,
    icon: ImageVector,
    iconSelected: ImageVector,
    count: Int?,
) {
    BadgedBox(
        badge = {
            AnimatedNewCounterBadge(
                count = count,
            )
        },
    ) {
        Crossfade(targetState = selected) {
            val vector = if (it) {
                iconSelected
            } else {
                icon
            }
            Icon(vector, null)
        }
    }
}

private fun navigateOnClick(
    controller: NavigationController,
    item: HomeNavigationItem,
) {
    val intent = NavigationIntent.Manual { factory ->
        val navStack = getStack(
            id = item.stackId,
        )
        val rootRoute = navStack.entries.firstOrNull()?.route
        // Dynamic home items can reuse a stack id while their resolved route
        // changes, so restore the stack only when its root still represents
        // the clicked item.
        val navEntries = if (
            rootRoute != null &&
            item.isSelected(navStack.entries) &&
            rootRoute.descriptor == item.route.descriptor
        ) {
            navStack.entries
        } else {
            val entry = factory(ROUTE_NAME, item.route)
            persistentListOf(entry)
        }
        navStack.copy(entries = navEntries)
    }
    controller.queue(intent)
}

private fun navigateSyncStatus(
    controller: NavigationController,
) {
    val intent = NavigationIntent.NavigateToRoute(SyncRoute)
    controller.queue(intent)
}
