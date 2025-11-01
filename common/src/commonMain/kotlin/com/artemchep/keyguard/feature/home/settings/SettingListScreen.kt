package com.artemchep.keyguard.feature.home.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginRoute
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRoute
import com.artemchep.keyguard.feature.home.settings.accounts.AccountListState
import com.artemchep.keyguard.feature.home.settings.accounts.AccountsSelection
import com.artemchep.keyguard.feature.home.settings.accounts.accountListScreenState
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.settings.autofill.AutofillSettingsRoute
import com.artemchep.keyguard.feature.home.settings.debug.DebugSettingsRoute
import com.artemchep.keyguard.feature.home.settings.display.UiSettingsRoute
import com.artemchep.keyguard.feature.home.settings.notifications.NotificationsSettingsRoute
import com.artemchep.keyguard.feature.home.settings.other.OtherSettingsRoute
import com.artemchep.keyguard.feature.home.settings.search.SearchSettingsRoute
import com.artemchep.keyguard.feature.home.settings.security.SecuritySettingsRoute
import com.artemchep.keyguard.feature.home.settings.subscriptions.SubscriptionsSettingsRoute
import com.artemchep.keyguard.feature.home.settings.watchtower.WatchtowerSettingsRoute
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.onboarding.SmallOnboardingCard
import com.artemchep.keyguard.feature.onboarding.onboardingItemsPremium
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.isStandalone
import com.artemchep.keyguard.platform.util.hasAutofill
import com.artemchep.keyguard.platform.util.hasSubscription
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardPremium
import com.artemchep.keyguard.ui.pulltosearch.PullToSearch
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

sealed interface SettingsItem2 {
    val id: String
}

data class SettingsItem(
    override val id: String,
    val title: TextHolder,
    val text: TextHolder,
    val icon: ImageVector? = null,
    val iconShape: RoundedPolygon? = null,
    val shapeState: Int = 0,
    val leading: (@Composable RowScope.() -> Unit)? = null,
    val trailing: (@Composable RowScope.() -> Unit)? = null,
    val footer: (@Composable ColumnScope.() -> Unit)? = null,
    val route: Route,
) : SettingsItem2

data class SettingsSectionItem(
    override val id: String,
    val title: TextHolder?,
) : SettingsItem2

data class SettingsAccountsItem(
    override val id: String,
    val state: State<AccountListState>,
) : SettingsItem2

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingListScreen() {
    val controller by rememberUpdatedState(LocalNavigationController.current)
    val r1 = registerRouteResultReceiver(BitwardenLoginRoute()) {
        controller.queue(NavigationIntent.Pop)
    }
    val r2 = registerRouteResultReceiver(KeePassLoginRoute) {
        controller.queue(NavigationIntent.Pop)
    }

    val accountsState = remember {
        val initialState = AccountListState()
        mutableStateOf(initialState)
    }

    val accountListStateWrapper = accountListScreenState(
        rootRouterName = SettingsRoute.ROUTER_NAME,
    )
    val accountListState = remember(accountListStateWrapper) {
        accountListStateWrapper.unwrap(
            onAddAccount = { accountType ->
                val route = when (accountType) {
                    AccountType.BITWARDEN -> r1
                    AccountType.KEEPASS -> r2
                }
                controller.queue(NavigationIntent.NavigateToRoute(route))
            },
        )
    }
    // Side effect:
    // Feed the accounts state to the account item.
    accountsState.value = accountListState

    val items = remember {
        val premiumShape = MaterialShapes.SoftBurst
        val optionsShape = MaterialShapes.Square

        val items = listOfNotNull<SettingsItem2>(
            SettingsAccountsItem(
                id = "accounts",
                state = accountsState,
            ),
            SettingsSectionItem(
                id = "section.premium",
                title = TextHolder.Res(Res.string.pref_section_premium_title),
            ).takeIf { CurrentPlatform.hasSubscription() && !isStandalone },
            SettingsItem(
                id = "subscription",
                title = TextHolder.Res(Res.string.pref_item_subscription_title),
                text = TextHolder.Res(Res.string.pref_item_subscription_text),
                iconShape = premiumShape,
                leading = {
                    val getPurchased by rememberInstance<GetPurchased>()
                    val isPurchased by remember(getPurchased) {
                        getPurchased()
                    }.collectAsState(false)

                    val targetTint =
                        if (isPurchased) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    val tint by animateColorAsState(targetValue = targetTint)
                    Icon(
                        Icons.Outlined.KeyguardPremium,
                        null,
                        tint = tint,
                    )
                },
                footer = {
                    Row(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(
                                start = Dimens.contentPadding + 42.dp,
                                end = Dimens.contentPadding,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        onboardingItemsPremium.forEach { item ->
                            SmallOnboardingCard(
                                modifier = Modifier,
                                title = stringResource(item.title),
                                text = stringResource(item.text),
                                imageVector = item.icon,
                            )
                        }
                    }
                },
                route = SubscriptionsSettingsRoute,
            ).takeIf { CurrentPlatform.hasSubscription() && !isStandalone },
            SettingsSectionItem(
                id = "section.options",
                title = TextHolder.Res(Res.string.pref_section_options_title),
            ),
            SettingsItem(
                id = "autofill",
                title = TextHolder.Res(Res.string.pref_item_autofill_title),
                text = TextHolder.Res(Res.string.pref_item_autofill_text),
                icon = Icons.Outlined.AutoAwesome,
                iconShape = optionsShape,
                route = AutofillSettingsRoute,
            ).takeIf { CurrentPlatform.hasAutofill() || !isRelease },
            SettingsItem(
                id = "security",
                title = TextHolder.Res(Res.string.pref_item_security_title),
                text = TextHolder.Res(Res.string.pref_item_security_text),
                icon = Icons.Outlined.Lock,
                iconShape = optionsShape,
                route = SecuritySettingsRoute,
            ),
            SettingsItem(
                id = "watchtower",
                title = TextHolder.Res(Res.string.pref_item_watchtower_title),
                text = TextHolder.Res(Res.string.pref_item_watchtower_text),
                icon = Icons.Outlined.Security,
                iconShape = optionsShape,
                route = WatchtowerSettingsRoute,
            ),
            SettingsItem(
                id = "notifications",
                title = TextHolder.Res(Res.string.pref_item_notifications_title),
                text = TextHolder.Res(Res.string.pref_item_notifications_text),
                icon = Icons.Outlined.Notifications,
                iconShape = optionsShape,
                route = NotificationsSettingsRoute,
            ).takeIf { !isRelease },
            SettingsItem(
                id = "display",
                title = TextHolder.Res(Res.string.pref_item_appearance_title),
                text = TextHolder.Res(Res.string.pref_item_appearance_text),
                icon = Icons.Outlined.ColorLens,
                iconShape = optionsShape,
                route = UiSettingsRoute,
            ),
            SettingsItem(
                id = "debug",
                title = TextHolder.Res(Res.string.pref_item_dev_title),
                text = TextHolder.Res(Res.string.pref_item_dev_text),
                icon = Icons.Outlined.Code,
                iconShape = optionsShape,
                route = DebugSettingsRoute,
            ).takeIf { !isRelease },
            SettingsItem(
                id = "about",
                title = TextHolder.Res(Res.string.pref_item_other_title),
                text = TextHolder.Res(Res.string.pref_item_other_text),
                icon = Icons.Outlined.Info,
                iconShape = optionsShape,
                route = OtherSettingsRoute,
            ),
        )
        items
            .mapIndexed { index, item ->
                when (item) {
                    is SettingsItem -> {
                        val shapeState = getShapeState(
                            list = items,
                            index = index,
                            predicate = { el, offset ->
                                el is SettingsItem
                            },
                        )
                        item.copy(
                            shapeState = shapeState,
                        )
                    }

                    else -> item
                }
            }
    }
    SettingListScreenContent(
        accountsState = accountsState,
        items = items,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterialApi::class,
)
@Composable
private fun SettingListScreenContent(
    accountsState: State<AccountListState>,
    items: List<SettingsItem2>,
) {
    val q = LocalNavigationController.current

    val pullRefreshState = rememberPullRefreshState(
        refreshing = false,
        onRefresh = {
            val route = SearchSettingsRoute
            val intent = NavigationIntent.NavigateToRoute(route)
            q.queue(intent)
        },
    )
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .pullRefresh(pullRefreshState)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.settings_main_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    IconButton(
                        onClick = {
                            val route = SearchSettingsRoute
                            val intent = NavigationIntent.NavigateToRoute(route)
                            q.queue(intent)
                        },
                    ) {
                        IconBox(Icons.Outlined.Search)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        pullRefreshState = pullRefreshState,
        overlay = {
            PullToSearch(
                modifier = Modifier
                    .padding(contentPadding.value),
                pullRefreshState = pullRefreshState,
            )
        },
        bottomBar = {
            val selectionOrNull = accountsState.value.selection
            AccountsSelection(
                selection = selectionOrNull,
            )
        },
    ) {
        items(items, key = { it.id }) {
            when (it) {
                is SettingsItem -> {
                    val backgroundColor = run {
                        if (LocalHasDetailPane.current) {
                            val nextEntry = navigationNextEntryOrNull()
                            val nextRoute = nextEntry?.route

                            val selected = it.route === nextRoute
                            if (selected) {
                                return@run MaterialTheme.colorScheme.selectedContainer
                            }
                        }

                        Color.Unspecified
                    }
                    SettingListItem(
                        backgroundColor = backgroundColor,
                        item = it,
                        onClick = {
                            val intent = NavigationIntent.Composite(
                                listOf(
                                    NavigationIntent.PopById(SettingsRoute.ROUTER_NAME),
                                    NavigationIntent.NavigateToRoute(it.route),
                                ),
                            )
                            q.queue(intent)
                        },
                    )
                }

                is SettingsSectionItem -> {
                    val text = it.title?.let { textResource(it) }
                    Section(
                        text = text,
                    )
                }

                is SettingsAccountsItem -> {
                    SettingListAccountsItem(
                        item = it,
                    )
                }
            }
        }
    }
}
