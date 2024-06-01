package com.artemchep.keyguard.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.artemchep.keyguard.common.model.DAccountStatus
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.usecase.GetAccountStatus
import com.artemchep.keyguard.common.usecase.GetNavLabel
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadCount
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.watchtower.WatchtowerRoute
import com.artemchep.keyguard.feature.home.settings.SettingsRoute
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeVisualStack
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.popById
import com.artemchep.keyguard.feature.send.SendRoute
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
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.infoContainer
import com.artemchep.keyguard.ui.theme.ok
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.rememberInstance

data class Rail(
    val key: String,
    val route: Route,
    val icon: ImageVector,
    val iconSelected: ImageVector,
    val label: TextHolder,
    val counterFlow: Flow<Int?> = flowOf(null),
)

private const val ROUTE_NAME = "home"

private val vaultRoute = VaultRoute(
    args = VaultRoute.Args(
        main = true,
    ),
)

private val sendsRoute = SendRoute()

private val generatorRoute = GeneratorRoute(
    args = GeneratorRoute.Args(
        password = true,
        username = true,
    ),
)

private val watchtowerRoute = WatchtowerRoute()

@Composable
fun HomeScreen(
    navBarVisible: Boolean = true,
) {
    val deeplinkService by rememberInstance<DeeplinkService>()
    val defaultRoute = remember {
        val defaultHome = deeplinkService.get(DeeplinkService.CUSTOM_HOME)
        when (defaultHome) {
            "generator" -> generatorRoute
            else -> vaultRoute
        }
    }

    val getWatchtowerUnreadCount by rememberInstance<GetWatchtowerUnreadCount>()

    val navRoutes = remember {
        persistentListOf(
            Rail(
                key = "vault",
                route = vaultRoute,
                icon = Icons.Outlined.Home,
                iconSelected = Icons.Filled.Home,
                label = TextHolder.Res(Res.string.home_vault_label),
            ),
            Rail(
                key = "sends",
                route = sendsRoute,
                icon = Icons.Outlined.Send,
                iconSelected = Icons.Filled.Send,
                label = TextHolder.Res(Res.string.home_send_label),
            ),
            Rail(
                key = "generator",
                route = generatorRoute,
                icon = Icons.Outlined.Password,
                iconSelected = Icons.Filled.Password,
                label = TextHolder.Res(Res.string.home_generator_label),
            ),
            Rail(
                key = "watchtower",
                route = watchtowerRoute,
                icon = Icons.Outlined.Security,
                iconSelected = Icons.Filled.Security,
                counterFlow = getWatchtowerUnreadCount()
                    .map { count ->
                        count.takeIf { it > 0 }
                    },
                label = TextHolder.Res(Res.string.home_watchtower_label),
            ),
            Rail(
                key = "settings",
                route = SettingsRoute,
                icon = Icons.Outlined.Settings,
                iconSelected = Icons.Filled.Settings,
                label = TextHolder.Res(Res.string.home_settings_label),
            ),
        )
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

@Composable
fun HomeScreenContent(
    backStack: PersistentList<NavigationEntry>,
    routes: ImmutableList<Rail>,
    navBarVisible: Boolean = true,
) {
    ResponsiveLayout {
        val horizontalInsets = WindowInsets.leStatusBars
            .union(WindowInsets.leNavigationBars)
            .union(WindowInsets.leDisplayCutout)
            .only(WindowInsetsSides.Start)
        Row(
            modifier = Modifier
                .windowInsetsPadding(horizontalInsets),
        ) {
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
                    val scrollState = rememberScrollState()
                    val verticalInsets = WindowInsets.leSystemBars
                        .union(WindowInsets.leIme)
                        .only(WindowInsetsSides.Vertical)
                    NavigationRail(
                        modifier = Modifier
                            .fillMaxHeight()
                            // When the keyboard is opened, there might be not
                            // enough space for all the items.
                            .verticalScroll(scrollState),
                        containerColor = Color.Transparent,
                        windowInsets = verticalInsets,
                    ) {
                        routes.forEach { r ->
                            key(r.key) {
                                val counterState = r.counterFlow.collectAsState(null)
                                RailNavigationControllerItem(
                                    backStack = backStack,
                                    route = r.route,
                                    icon = r.icon,
                                    iconSelected = r.iconSelected,
                                    count = counterState.value,
                                    label = if (navLabelState.value) {
                                        // composable
                                        {
                                            Text(
                                                text = textResource(r.label),
                                                maxLines = 2,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        Spacer(
                            modifier = Modifier
                                .weight(1f),
                        )
                        Column(
                            modifier = Modifier
                                .padding(top = 16.dp)
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
                mutableStateOf(80.dp)
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
                    visible = bottomNavBarVisible,
                ) {
                    val updatedDensity by rememberUpdatedState(LocalDensity.current)
                    Box(
                        modifier = Modifier
                            .padding(bottomInsets.asPaddingValues())
                            .onSizeChanged {
                                val heightDp = (it.height.toFloat() / updatedDensity.density).dp
                                bottomNavBarSize = heightDp
                                    .coerceAtLeast(80.dp)
                            },
                    ) {
                        Column(
                            modifier = Modifier,
                        ) {
                            BannerStatusBadge(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                statusState = accountStatusState,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .selectableGroup(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                routes.forEach { r ->
                                    key(r.key) {
                                        val counterState = r.counterFlow.collectAsState(null)
                                        BottomNavigationControllerItem(
                                            backStack = backStack,
                                            route = r.route,
                                            icon = r.icon,
                                            iconSelected = r.iconSelected,
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
                            }
                        }

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
                    }
                }
            }
        }
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
                        title = TextHolder.Value("Notifications are disabled"),
                        text = TextHolder.Value("Grant the notification permission to allow Keyguard to show one-time passwords when autofilling & more."),
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
                RailStatusBadgeContent(
                    contentColor = MaterialTheme.colorScheme.ok,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                        )
                    },
                    text = stringResource(Res.string.syncstatus_status_up_to_date),
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
) {
    Column(
        modifier = modifier
            .padding(
                horizontal = 4.dp,
                vertical = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = contentColor,
        )
    }
}

@Composable
private fun ColumnScope.RailNavigationControllerItem(
    backStack: ImmutableList<NavigationEntry>,
    route: Route,
    icon: ImageVector,
    iconSelected: ImageVector,
    count: Int?,
    label: @Composable (() -> Unit)? = null,
) {
    val controller = LocalNavigationController.current
    val selected = isSelected(backStack, route)
    NavigationRailItem(
        icon = {
            NavigationIcon(
                selected = selected,
                icon = icon,
                iconSelected = iconSelected,
                count = count,
            )
        },
        label = label,
        selected = selected,
        colors = navigationRailItemColors(),
        onClick = {
            navigateOnClick(controller, backStack, route)
        },
    )
}

@Composable
private fun RowScope.BottomNavigationControllerItem(
    backStack: ImmutableList<NavigationEntry>,
    route: Route,
    icon: ImageVector,
    iconSelected: ImageVector,
    count: Int?,
    label: @Composable (() -> Unit)? = null,
) {
    val controller = LocalNavigationController.current
    val selected = isSelected(backStack, route)
    NavigationBarItem(
        icon = {
            NavigationIcon(
                selected = selected,
                icon = icon,
                iconSelected = iconSelected,
                count = count,
            )
        },
        label = label,
        selected = selected,
        colors = navigationBarItemColors(),
        onClick = {
            navigateOnClick(controller, backStack, route)
        },
    )
}

@Composable
private fun navigationRailItemColors(): NavigationRailItemColors {
    return NavigationRailItemDefaults.colors()
}

@Composable
private fun navigationBarItemColors(): NavigationBarItemColors {
    return NavigationBarItemDefaults.colors()
}

private fun isSelected(
    backStack: List<NavigationEntry>,
    route: Route,
) = run {
    val entry = backStack.getOrNull(1) ?: backStack.firstOrNull()
    entry?.route === route
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
    backStack: List<NavigationEntry>,
    route: Route,
) {
    val intent = NavigationIntent.Manual { factory ->
        // If the route exists in the stack, then simply
        // navigate back to it.
        val indexOfRoute = backStack.indexOfFirst { it.route === route }
        if (indexOfRoute != -1 && indexOfRoute <= 1) {
            return@Manual subList(0, indexOfRoute + 1)
                .toPersistentList()
        }

        val stack = popById(ROUTE_NAME, exclusive = true)
            .orEmpty()
            .toPersistentList()
        stack.add(factory(route))
    }
    controller.queue(intent)
}

private fun navigateSyncStatus(
    controller: NavigationController,
) {
    val intent = NavigationIntent.NavigateToRoute(SyncRoute)
    controller.queue(intent)
}
